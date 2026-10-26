package com.xtim.meowify

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.chaquo.python.Python
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket

class ServerService : LifecycleService() {

    companion object {
        private const val TAG = "MeowifyServer"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.xtim.meowify.START_SERVER"
        const val ACTION_STOP  = "com.xtim.meowify.STOP_SERVER"
        const val EXTRA_PORT   = "port"

        const val ACTION_MEDIA_PLAY  = "com.xtim.meowify.MEDIA_PLAY"
        const val ACTION_MEDIA_PAUSE = "com.xtim.meowify.MEDIA_PAUSE"
        const val ACTION_MEDIA_NEXT  = "com.xtim.meowify.MEDIA_NEXT"
        const val ACTION_MEDIA_PREV  = "com.xtim.meowify.MEDIA_PREV"
        const val CUSTOM_ACTION_SHUFFLE = "com.xtim.meowify.SHUFFLE"
        const val CUSTOM_ACTION_REPEAT  = "com.xtim.meowify.REPEAT"

        // cross-process messaging (MainActivity runs in :webview, separate from
        // this service). Service -> Activity (media button/notification/duck)
        // goes over an ordered broadcast; Activity -> Service (onPlaybackState)
        // goes over the Messenger returned from onBind.
        const val ACTION_MEDIA_EVENT = "com.xtim.meowify.MEDIA_EVENT"
        const val EXTRA_MEDIA_ACTION = "media_action"
        const val EXTRA_SEEK_MS = "seek_ms"

        const val MSG_PLAYBACK_STATE = 1
        const val KEY_PLAYING = "playing"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_COVER_PATH = "cover_path"
        const val KEY_DURATION_SEC = "duration_sec"
        const val KEY_POSITION_SEC = "position_sec"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"

        @Volatile var serverPort: Int = 0

        fun getPort(context: Context): Int {
            if (serverPort != 0) return serverPort
            // .port is written by android_patch.py into ddir(), which resolves to
            // getExternalFilesDir(null) (see MEOWIFY_DATA_DIR) — not filesDir.
            val pf = File(context.getExternalFilesDir(null) ?: context.filesDir, "meowify/.port")
            return if (pf.exists()) pf.readText().trim().toIntOrNull() ?: 0 else 0
        }
    }

    /** Implemented by MainActivity to forward transport commands into the webview JS. */
    interface PlaybackController {
        fun onMediaPlay()
        fun onMediaPause()
        fun onMediaNext()
        fun onMediaPrev()
        fun onMediaShuffle()
        fun onMediaRepeat()
        fun onMediaSeekTo(positionMs: Long)
        fun onMediaDuck(duck: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): ServerService = this@ServerService
    }

    private val binder = LocalBinder()
    @Volatile var playbackController: PlaybackController? = null

    // handles incoming Messenger calls from MainActivity (cross-process)
    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_PLAYBACK_STATE) {
            val b = msg.data
            updatePlaybackState(
                playing = b.getBoolean(KEY_PLAYING),
                title = b.getString(KEY_TITLE, ""),
                artist = b.getString(KEY_ARTIST, ""),
                album = b.getString(KEY_ALBUM, ""),
                coverPath = b.getString(KEY_COVER_PATH, ""),
                durationSec = b.getDouble(KEY_DURATION_SEC),
                positionSec = b.getDouble(KEY_POSITION_SEC),
                shuffle = b.getBoolean(KEY_SHUFFLE),
                repeat = b.getString(KEY_REPEAT, "none")
            )
        }
        true
    }
    private val messenger = Messenger(incomingHandler)

    private fun sendMediaEvent(action: String, seekMs: Long? = null) {
        val intent = Intent(ACTION_MEDIA_EVENT).apply {
            setPackage(packageName)
            putExtra(EXTRA_MEDIA_ACTION, action)
            seekMs?.let { putExtra(EXTRA_SEEK_MS, it) }
        }
        sendBroadcast(intent)
    }

    private var serverThread: Thread? = null
    // set once startServer() has been invoked for this process's lifetime -
    // never reset, since the Python side (Chaquopy interpreter, meowify
    // module cache) is also process-wide and cannot be safely re-initialized
    // by a second startServer() call. See onStartCommand and main_android().
    private var serverStartAttempted = false
    private var wakeLock: PowerManager.WakeLock? = null
    // timestamp of our last requestAudioFocus() call — used to ignore the
    // intra-app focus loss that chromium fires back at us within ~1s of us
    // requesting focus (both sides request AUDIOFOCUS_GAIN for the same stream)
    private var lastFocusRequestMs: Long = 0L
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // last known playback info, used to rebuild notification/session on focus changes etc
    private var lastPlaying = false
    private var lastShuffle = false
    private var lastRepeat  = "none"   // "none" | "all" | "one"
    private var lastTitle = ""
    private var lastArtist = ""
    private var lastAlbum = ""
    private var lastDurationMs = 0L
    private var lastPositionMs = 0L
    private var lastCoverPath = ""
    private var lastCoverBitmap: Bitmap? = null

    // ── lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        setupMediaSession()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        // MainActivity always binds cross-process now (:webview), so it needs
        // the Messenger binder, not the old same-process LocalBinder.
        return messenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MEDIA_PLAY -> { requestAudioFocus(); sendMediaEvent("play") }
            ACTION_MEDIA_PAUSE -> sendMediaEvent("pause")
            ACTION_MEDIA_NEXT -> sendMediaEvent("next")
            ACTION_MEDIA_PREV -> sendMediaEvent("prev")
            CUSTOM_ACTION_SHUFFLE -> sendMediaEvent("shuffle")
            CUSTOM_ACTION_REPEAT  -> sendMediaEvent("repeat")
            else -> {
                startForeground(NOTIF_ID, buildNotification("starting..."))
                // serverThread.isAlive alone isn't a trustworthy guard: Werkzeug's
                // dev server blocks on a socket accept loop that Thread.interrupt()
                // cannot break out of, so a thread can be "alive" indefinitely after
                // onDestroy() tried to stop it, and this branch can also legitimately
                // re-run if Android restarts the service (START_STICKY) while the
                // underlying Python interpreter/module cache survived the restart
                // (Chaquopy's interpreter is process-wide, see MeowifyApp.kt). Only
                // ever call startServer() once per process lifetime; main_android()
                // itself also refuses a second run as a last-resort guard on the
                // Python side, but this is the fix that actually prevents hitting it.
                if (!serverStartAttempted) {
                    serverStartAttempted = true
                    startServer()
                } else {
                    Log.i(TAG, "onStartCommand: server already started this process lifetime, skipping")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serverThread?.interrupt()
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        lastCoverBitmap?.recycle()
        lastCoverBitmap = null
        wakeLock?.release()
        super.onDestroy()
    }

    // ── media session ──────────────────────────────────────────────────────────

    private fun setupMediaSession() {
        val session = MediaSessionCompat(this, "meowify")

        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                requestAudioFocus()
                sendMediaEvent("play")
            }
            override fun onPause() {
                sendMediaEvent("pause")
            }
            override fun onSkipToNext() {
                sendMediaEvent("next")
            }
            override fun onSkipToPrevious() {
                sendMediaEvent("prev")
            }
            override fun onSeekTo(pos: Long) {
                sendMediaEvent("seek", pos)
            }
            override fun onStop() {
                sendMediaEvent("pause")
            }
            override fun onCustomAction(action: String, extras: Bundle?) {
                when (action) {
                    CUSTOM_ACTION_SHUFFLE -> sendMediaEvent("shuffle")
                    CUSTOM_ACTION_REPEAT  -> sendMediaEvent("repeat")
                }
            }
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                val ke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (ke?.action == KeyEvent.ACTION_DOWN) {
                    when (ke.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY        -> { requestAudioFocus(); sendMediaEvent("play") }
                        KeyEvent.KEYCODE_MEDIA_PAUSE       -> sendMediaEvent("pause")
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE  -> {
                            if (lastPlaying) sendMediaEvent("pause")
                            else { requestAudioFocus(); sendMediaEvent("play") }
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT        -> sendMediaEvent("next")
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS    -> sendMediaEvent("prev")
                        KeyEvent.KEYCODE_MEDIA_STOP        -> sendMediaEvent("pause")
                        else -> return false
                    }
                    return true
                }
                return false
            }
        })

        // initial idle state so the system shows controls immediately
        session.setPlaybackState(buildPlaybackState(false, 0L))
        session.isActive = true
        mediaSession = session
    }

    private fun buildPlaybackState(playing: Boolean, positionMs: Long): PlaybackStateCompat {
        val stateVal = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val shuffleIcon = if (lastShuffle) R.drawable.ic_shuffle_active else R.drawable.ic_shuffle
        val shuffleAction = PlaybackStateCompat.CustomAction.Builder(
            CUSTOM_ACTION_SHUFFLE, "shuffle", shuffleIcon
        ).build()
        val repeatIcon = when (lastRepeat) {
            "all" -> R.drawable.ic_repeat_active
            "one" -> R.drawable.ic_repeat_one_active
            else  -> R.drawable.ic_repeat
        }
        val repeatAction = PlaybackStateCompat.CustomAction.Builder(
            CUSTOM_ACTION_REPEAT, "repeat", repeatIcon
        ).build()

        return PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
            .addCustomAction(shuffleAction)
            .addCustomAction(repeatAction)
            .setState(stateVal, positionMs, 1f)
            .build()
    }

    /**
     * Called from MainActivity (via the JS bridge) whenever meowify's
     * playback state changes. Keeps the media session, lock screen,
     * and notification in sync.
     *
     * durationSec/positionSec are in seconds (as reported by the web audio engine);
     * coverPath is the absolute file path to the song's cover art, or empty.
     */
    fun updatePlaybackState(
        playing: Boolean,
        title: String,
        artist: String,
        album: String,
        coverPath: String,
        durationSec: Double,
        positionSec: Double,
        shuffle: Boolean,
        repeat: String = "none"
    ) {
        val session = mediaSession ?: return

        lastPlaying = playing
        lastShuffle = shuffle
        lastRepeat  = repeat
        lastTitle = title
        lastArtist = artist
        lastAlbum = album
        lastDurationMs = (durationSec * 1000).toLong().coerceAtLeast(0L)
        lastPositionMs = (positionSec * 1000).toLong().coerceAtLeast(0L)

        if (playing && !hasAudioFocus) requestAudioFocus()

        if (title.isNotEmpty() && coverPath != lastCoverPath) {
            lastCoverPath = coverPath
            lastCoverBitmap?.recycle()
            lastCoverBitmap = loadCoverBitmap(coverPath)
        } else if (title.isEmpty()) {
            lastCoverPath = ""
            lastCoverBitmap?.recycle()
            lastCoverBitmap = null
        }

        if (title.isNotEmpty()) {
            val metaBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, lastDurationMs)
            lastCoverBitmap?.let {
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            }
            session.setMetadata(metaBuilder.build())
        } else {
            session.setMetadata(null)
        }

        session.setPlaybackState(buildPlaybackState(playing, lastPositionMs))
        updateNotificationFromState()
    }

    private fun loadCoverBitmap(path: String): Bitmap? {
        if (path.isEmpty()) return null
        return try {
            val f = File(path)
            if (!f.exists()) return null
            // downscale to a reasonable size for notifications/lockscreen
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            var sample = 1
            while (opts.outWidth / sample > 512 || opts.outHeight / sample > 512) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, decodeOpts)
        } catch (e: Exception) {
            Log.w(TAG, "failed to load cover art from $path", e)
            null
        }
    }

    // ── audio focus ────────────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val am = audioManager ?: getSystemService(AudioManager::class.java).also { audioManager = it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { focusChange ->
                    val msSinceRequest = System.currentTimeMillis() - lastFocusRequestMs
                    val likelyIntraApp = msSinceRequest < 2000L
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // transient loss (phone call, navigation TTS, etc) — pause
                            hasAudioFocus = false
                            sendMediaEvent("pause")
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            if (likelyIntraApp) {
                                // chromium WebView bouncing focus back within the same app —
                                // ignore so we don't re-request and cause a storm
                            } else {
                                // genuine external loss (another music app, etc) — pause
                                hasAudioFocus = false
                                sendMediaEvent("pause")
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            sendMediaEvent("duck")
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            hasAudioFocus = true
                            sendMediaEvent("unduck")
                        }
                    }
                }
                .build()
            audioFocusRequest = req
            lastFocusRequestMs = System.currentTimeMillis()
            hasAudioFocus = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            lastFocusRequestMs = System.currentTimeMillis()
            @Suppress("DEPRECATION")
            hasAudioFocus = am.requestAudioFocus(
                { focusChange ->
                    val msSinceRequest = System.currentTimeMillis() - lastFocusRequestMs
                    val likelyIntraApp = msSinceRequest < 2000L
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            hasAudioFocus = false
                            sendMediaEvent("pause")
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            if (!likelyIntraApp) {
                                hasAudioFocus = false
                                sendMediaEvent("pause")
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                            sendMediaEvent("duck")
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            hasAudioFocus = true
                            sendMediaEvent("unduck")
                        }
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        }
        hasAudioFocus = false
    }

    // ── server startup ─────────────────────────────────────────────────────────

    private fun startServer() {
        serverThread = Thread({
            try {
                setupEnvironment()
                val port = findFreePort()
                serverPort = port
                // write .port ourselves — don't rely on android_patch.py (python side)
                // to do this. MainActivity now runs in a separate process
                // (android:process=":webview"), so the in-memory `serverPort` static
                // above is invisible to it; this file is the only cross-process signal.
                try {
                    val pf = File((getExternalFilesDir(null) ?: filesDir), "meowify/.port")
                    pf.parentFile?.mkdirs()
                    pf.writeText(port.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "failed writing .port file", e)
                }
                Log.i(TAG, "starting flask on port $port")
                updateNotification("running on :$port")

                // dataDir = where music/covers/library.db live (external, user-facing storage)
                val dataDir = (getExternalFilesDir(null) ?: filesDir).absolutePath
                // codeDir = where android_patch.py / meowify.py / motionui were actually
                // extracted to by setupEnvironment() — filesDir/meowify, NOT dataDir.
                // these were previously conflated, so sys.path pointed at dataDir and
                // `import android_patch` failed with ModuleNotFoundError on every launch.
                val codeDir = File(filesDir, "meowify").absolutePath
                val ffmpeg  = ffmpegPath().absolutePath
                val ffDir   = ffmpegPath().parent ?: ""
                val oldPath = System.getenv("PATH") ?: ""

                // set native env BEFORE Python.getInstance() so python sees them at import time
                android.system.Os.setenv("MEOWIFY_DATA_DIR", dataDir, true)
                android.system.Os.setenv("MEOWIFY_FFMPEG",     ffmpeg, true)
                android.system.Os.setenv("MEOWIFY_PORT",       port.toString(), true)
                android.system.Os.setenv("MEOWIFY_ANDROID",    "1", true)
                android.system.Os.setenv("PATH",               "$ffDir:$oldPath", true)

                val py = Python.getInstance()

                val osModule  = py.getModule("os")
                val environ   = osModule.get("environ")
                environ!!.put("MEOWIFY_DATA_DIR", dataDir)
                environ.put("MEOWIFY_FFMPEG",     ffmpeg)
                environ.put("MEOWIFY_PORT",       port.toString())
                environ.put("MEOWIFY_ANDROID",    "1")
                environ.put("PATH",               "$ffDir:$oldPath")

                // prepend codeDir (not dataDir) to sys.path so android_patch.py and
                // meowify.py — extracted to filesDir/meowify — are actually importable.
                // chaquopy also adds assets/ to sys.path automatically for files in assets/
                py.getModule("sys").get("path")!!.callAttr("insert", 0, codeDir)

                Log.i(TAG, "env set, importing android_patch from $codeDir")

                // run the server via android_patch — blocks until killed
                val patch = py.getModule("android_patch")
                patch.callAttr("main_android", port, dataDir)

            } catch (e: InterruptedException) {
                Log.i(TAG, "server thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "server crashed", e)
                updateNotification("crashed - restart app")
            }
        }, "meowify-flask")
        serverThread!!.isDaemon = true
        serverThread!!.start()
    }

    // ── environment setup ──────────────────────────────────────────────────────

    private fun setupEnvironment() {
        // extract ffmpeg binary — re-extracts whenever the installed APK's
        // lastUpdateTime changes, so a bundled ffmpeg update always lands
        // regardless of whether anyone remembered to bump versionCode
        val ff = ffmpegPath()
        val ffStamp = File(ff.parentFile, ".ffmpeg_stamp")
        if (!ff.exists() || ff.length() < 1_000_000L || ffStamp.readText2() != currentApkStamp()) {
            ff.parentFile?.mkdirs()
            assets.open("ffmpeg").use { input ->
                FileOutputStream(ff).use { output -> input.copyTo(output) }
            }
            ff.setExecutable(true, false)
            ffStamp.writeText(currentApkStamp())
            Log.i(TAG, "ffmpeg extracted to ${ff.absolutePath}")
        }

        // extract motionui from assets on first run / apk update
        val muDir = File(filesDir, "meowify/motionui")
        extractAssetDir("motionui", muDir)

        // extract meowify.py (always overwrite — picks up updates)
        val pyFile = File(filesDir, "meowify/meowify.py")
        pyFile.parentFile?.mkdirs()
        assets.open("meowify.py").use { input ->
            FileOutputStream(pyFile).use { output -> input.copyTo(output) }
        }

        // extract android_patch.py (always overwrite)
        val patchFile = File(filesDir, "meowify/android_patch.py")
        assets.open("android_patch.py").use { input ->
            FileOutputStream(patchFile).use { output -> input.copyTo(output) }
        }

        // write motionui dir path marker for python
        File(filesDir, "meowify/.motionui_dir").writeText(muDir.absolutePath)

        // ensure music/covers dirs exist under the external data dir
        val extBase = getExternalFilesDir(null) ?: filesDir
        for (sub in listOf("music", "covers")) {
            File(extBase, sub).mkdirs()
        }
    }

    /** lastUpdateTime is set by the OS on every install/update of this APK package —
     *  unlike versionCode, it can't be forgotten or left un-bumped by mistake, so it's
     *  a fully regression-proof signal for "the bundled assets may have changed." */
    private fun currentApkStamp(): String = try {
        packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
    } catch (e: Exception) { "0" }

    private fun File.readText2(): String = try { readText() } catch (e: Exception) { "" }

    private fun extractAssetDir(assetPath: String, destDir: File) {
        val stamp = File(destDir.parent, ".${destDir.name}_stamp")
        val currentVersion = currentApkStamp()

        if (stamp.exists() && stamp.readText() == currentVersion && destDir.exists()) return

        destDir.deleteRecursively()
        destDir.mkdirs()

        fun extractRecursive(asset: String, dest: File) {
            val children = assets.list(asset) ?: emptyArray()
            if (children.isEmpty()) {
                dest.parentFile?.mkdirs()
                assets.open(asset).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
            } else {
                dest.mkdirs()
                for (child in children) {
                    extractRecursive("$asset/$child", File(dest, child))
                }
            }
        }

        extractRecursive(assetPath, destDir)
        stamp.writeText(currentVersion)
        Log.i(TAG, "extracted $assetPath to $destDir")
    }

    private fun ffmpegPath(): File = File(filesDir, "bin/ffmpeg")

    private fun findFreePort(): Int {
        // same dir android_patch.py writes .port into (ddir() == MEOWIFY_DATA_DIR ==
        // getExternalFilesDir(null)) — was previously reading from filesDir, where
        // the file never gets written, making this cache permanently dead
        val pf = File((getExternalFilesDir(null) ?: filesDir), "meowify/.port")
        // check cached port first
        if (pf.exists()) {
            val cached = pf.readText().trim().toIntOrNull()
            if (cached != null && cached > 1024) {
                try { ServerSocket(cached).use { }; return cached } catch (_: Exception) {}
            }
        }
        // try the meow port (60369 = MEOW on phone keypad: 6-0-3-6-9)
        try { ServerSocket(60369).use { }; return 60369 } catch (_: Exception) {}
        // fall back to any free port the OS gives us
        return ServerSocket(0).use { it.localPort }
    }

    // ── notifications ──────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MeowifyApp.CHANNEL_SERVER)
            .setContentTitle("meowify")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, "stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    /** Rebuilds the foreground notification as a MediaStyle notification reflecting current playback. */
    private fun updateNotificationFromState() {
        val session = mediaSession ?: return
        val nm = getSystemService(NotificationManager::class.java)

        if (lastTitle.isEmpty()) {
            nm.notify(NOTIF_ID, buildNotification(
                if (serverPort != 0) "running on :$serverPort" else "starting..."
            ))
            return
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (lastPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseTitle = if (lastPlaying) "pause" else "play"

        val prevAction = NotificationCompat.Action(
            R.drawable.ic_skip_prev, "previous",
            buildServiceActionPendingIntent(ACTION_MEDIA_PREV)
        )
        val playPauseAction = NotificationCompat.Action(
            playPauseIcon, playPauseTitle,
            buildServiceActionPendingIntent(if (lastPlaying) ACTION_MEDIA_PAUSE else ACTION_MEDIA_PLAY)
        )
        val nextAction = NotificationCompat.Action(
            R.drawable.ic_skip_next, "next",
            buildServiceActionPendingIntent(ACTION_MEDIA_NEXT)
        )
        val shuffleIcon = if (lastShuffle) R.drawable.ic_shuffle_active else R.drawable.ic_shuffle
        val shuffleAction = NotificationCompat.Action(
            shuffleIcon, "shuffle",
            buildServiceActionPendingIntent(CUSTOM_ACTION_SHUFFLE)
        )
        val repeatIcon = when (lastRepeat) {
            "all" -> R.drawable.ic_repeat_active
            "one" -> R.drawable.ic_repeat_one_active
            else  -> R.drawable.ic_repeat
        }
        val repeatAction = NotificationCompat.Action(
            repeatIcon, "repeat",
            buildServiceActionPendingIntent(CUSTOM_ACTION_REPEAT)
        )

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(1, 2, 3)  // compact: prev/playpause/next

        val builder = NotificationCompat.Builder(this, MeowifyApp.CHANNEL_SERVER)
            .setContentTitle(lastTitle)
            .setContentText(if (lastAlbum.isNotEmpty()) "$lastArtist · $lastAlbum" else lastArtist)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(shuffleAction)   // slot 0
            .addAction(prevAction)      // slot 1
            .addAction(playPauseAction) // slot 2
            .addAction(nextAction)      // slot 3
            .addAction(repeatAction)    // slot 4
            .setStyle(mediaStyle)
            .setOngoing(lastPlaying)
            .setSilent(true)
            .setOnlyAlertOnce(true)

        lastCoverBitmap?.let { builder.setLargeIcon(it) }

        nm.notify(NOTIF_ID, builder.build())
    }

    private fun buildServiceActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, ServerService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── wake lock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        // no timeout — held for the lifetime of the foreground service, released in onDestroy.
        // safe because the service is only alive while the user wants background playback;
        // an expiring lock (e.g. 12h) silently lets the system throttle long listening sessions.
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "meowify:server"
        ).apply { setReferenceCounted(false); acquire() }
    }
}
