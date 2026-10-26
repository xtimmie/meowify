package com.xtim.meowify

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.xtim.meowify.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MeowifyMain"
        // first launch chaquopy init can take ~30s; subsequent launches ~3s
        private const val MAX_WAIT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 100L
    }

    private lateinit var binding: ActivityMainBinding
    private var webView: WebView? = null
    private var pollingThread: Thread? = null

    private var serviceBound = false

    // Messenger to ServerService (cross-process: this activity runs in :webview,
    // separate from the default process ServerService lives in). Used for the
    // Activity -> Service leg (onPlaybackState). null while unbound.
    private var serviceMessenger: Messenger? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            serviceMessenger = Messenger(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceMessenger = null
        }
    }

    // Service -> Activity: media button/notification/duck events arrive here as
    // a broadcast, since ServerService runs in a different process and can no
    // longer call back into this activity directly.
    private val mediaEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.getStringExtra(ServerService.EXTRA_MEDIA_ACTION) ?: return
            when (action) {
                "duck"   -> runJs("if(typeof _androidDuck==='function')_androidDuck();")
                "unduck" -> runJs("if(typeof _androidUnduck==='function')_androidUnduck();")
                "seek"   -> {
                    val ms = intent.getLongExtra(ServerService.EXTRA_SEEK_MS, 0L)
                    runJs("if(typeof _androidSeek==='function')_androidSeek(${ms / 1000.0});")
                }
                else -> dispatchMediaAction(action)
            }
        }
    }

    private fun runJs(js: String) {
        runOnUiThread { webView?.evaluateJavascript(js, null) }
    }

    // ── file picker ────────────────────────────────────────────────────────────
    private var filePickerCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePickerCallback
        filePickerCallback = null
        if (result.resultCode == RESULT_OK) {
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            val tmpUris = uris?.mapNotNull { copyToCache(it) }?.map { Uri.fromFile(it) }
            cb?.onReceiveValue(tmpUris?.toTypedArray())
        } else {
            cb?.onReceiveValue(null)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.i(TAG, "permissions result: $results")
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNeededPermissions()
        requestBatteryOptimizationExemption()
        startServerService()
        bindServerService()
        registerMediaEventReceiver()
        setupWebView()
        waitForServerAndLoad()
        handleDropboxCallback(intent)
    }

    private fun registerMediaEventReceiver() {
        val filter = IntentFilter(ServerService.ACTION_MEDIA_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(mediaEventReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDropboxCallback(intent)
    }

    private fun handleDropboxCallback(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "meowify" || uri.host != "dropbox-callback") return
        val code = uri.getQueryParameter("code") ?: return
        Log.i(TAG, "dropbox callback received, code length=${code.length}")
        Thread {
            try {
                val port = ServerService.getPort(this)
                if (port == 0) { Log.e(TAG, "dropbox callback: server port not known yet"); return@Thread }
                val url = java.net.URL("http://127.0.0.1:$port/api/dropbox/submit-code")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write("""{"code":"$code"}""".toByteArray())
                val status = conn.responseCode
                conn.disconnect()
                Log.i(TAG, "dropbox submit-code response: $status")
                runOnUiThread {
                    webView?.loadUrl("http://127.0.0.1:$port/#settings")
                }
            } catch (e: Exception) {
                Log.e(TAG, "dropbox callback POST failed", e)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(mediaEventReceiver) } catch (e: Exception) { /* not registered */ }
        unbindServerService()
        webView?.destroy()
        super.onDestroy()
    }

    // back button navigates webview history; media keys forwarded to player
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView?.canGoBack() == true) {
            webView?.goBack()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY        -> { dispatchMediaAction("play");  return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE       -> { dispatchMediaAction("pause"); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE  -> { dispatchMediaAction("playpause"); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT        -> { dispatchMediaAction("next");  return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS    -> { dispatchMediaAction("prev");  return true }
            KeyEvent.KEYCODE_MEDIA_STOP        -> { dispatchMediaAction("pause"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── media events from ServerService now arrive via mediaEventReceiver ───────
    // (ServerService runs in a different process; see mediaEventReceiver above)

    // dispatches a media action into the webview via the JS bridge meowify already has
    private fun dispatchMediaAction(action: String) {
        val js = when (action) {
            "play"      -> "if(typeof _androidPlay==='function')_androidPlay();"
            "pause"     -> "if(typeof _androidPause==='function')_androidPause();"
            "playpause" -> "if(typeof togglePlay==='function')togglePlay();"
            "next"      -> "if(typeof _androidNext==='function')_androidNext();"
            "prev"      -> "if(typeof _androidPrev==='function')_androidPrev();"
            "shuffle"   -> "if(typeof _androidShuffle==='function')_androidShuffle();"
            "repeat"    -> "if(typeof _androidRepeat==='function')_androidRepeat();"
            else        -> return
        }
        runOnUiThread { webView?.evaluateJavascript(js, null) }
    }

    // ── service ────────────────────────────────────────────────────────────────

    private fun startServerService() {
        val intent = Intent(this, ServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun bindServerService() {
        // real cross-process bind — ServerService.onBind returns a Messenger
        // binder (see ServerService.kt), which works across processes unlike
        // the old same-process LocalBinder.
        try {
            val intent = Intent(this, ServerService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_ABOVE_CLIENT)
            serviceBound = true
        } catch (e: Exception) {
            Log.e(TAG, "bindServerService failed", e)
        }
    }

    private fun unbindServerService() {
        if (!serviceBound) return
        unbindService(serviceConnection)
        serviceMessenger = null
        serviceBound = false
    }

    // ── webview ────────────────────────────────────────────────────────────────

    private fun setupWebView() {
        val wv = binding.webview
        webView = wv

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            // allow audio to play without user gesture — essential for meowify
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            // always reload from flask, never serve stale cache
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // larger text encoding buffer for meowify's large HTML payload
            defaultTextEncodingName = "UTF-8"
        }

        // prevent system from forcing light mode over meowify's dark theme
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, false)
        }

        wv.addJavascriptInterface(MeowifyBridge(), "AndroidBridge")

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                // cancel any pending callback first
                filePickerCallback?.onReceiveValue(null)
                filePickerCallback = callback
                filePicker.launch(params.createIntent())
                return true
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d("WebConsole", "[${msg.messageLevel()}] ${msg.message()} @ ${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Log.e(TAG, "webview main frame error: ${error.description} (${error.errorCode})")
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // keep localhost traffic inside the webview
                if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                    return false
                }
                // external URLs open in the system browser
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                    Log.e(TAG, "could not open external url: $url", e)
                }
                return true
            }
        }
    }

    // ── server polling ─────────────────────────────────────────────────────────

    private fun waitForServerAndLoad() {
        showLoading(true)

        pollingThread = Thread {
            val start = System.currentTimeMillis()
            var port = 0

            while (System.currentTimeMillis() - start < MAX_WAIT_MS) {
                if (isDestroyed) { Log.w(TAG, "activity destroyed, poll exit"); return@Thread }
                try {
                    port = ServerService.getPort(this)
                    val open = port != 0 && isPortOpen(port)
                    Log.d(TAG, "polling: port=$port open=$open")
                    if (open) break
                } catch (e: Exception) {
                    Log.e(TAG, "polling loop exception", e)
                }
                try { Thread.sleep(POLL_INTERVAL_MS) } catch (e: InterruptedException) { Log.w(TAG, "polling thread interrupted!"); return@Thread }
            }

            val finalPort = port
            if (isDestroyed) { Log.w(TAG, "activity destroyed after poll, bailing"); return@Thread }
            runOnUiThread {
                if (finalPort == 0) {
                    showLoading(false)
                    binding.errorView.visibility = View.VISIBLE
                    Toast.makeText(this, "server failed to start — check notification", Toast.LENGTH_LONG).show()
                } else {
                    showLoading(false)
                    binding.webview.visibility = View.VISIBLE
                    val url = "http://127.0.0.1:$finalPort"
                    Log.i(TAG, "server ready, loading $url")
                    webView?.loadUrl(url)
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "meowify-poll"
            it.start()
        }
    }

    private fun showLoading(show: Boolean) {
        binding.loadingView.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.errorView.visibility = View.GONE
            binding.webview.visibility = View.GONE
        }
    }

    // called by the "retry" button in the error view (android:onClick="retryStart")
    fun retryStart(view: View) {
        binding.errorView.visibility = View.GONE
        startServerService()
        waitForServerAndLoad()
    }

    private fun isPortOpen(port: Int): Boolean {
        repeat(8) {
            try { java.net.Socket("127.0.0.1", port).use { return true } } catch (_: Exception) {}
            Thread.sleep(15)
        }
        return false
    }

    // ── permissions ────────────────────────────────────────────────────────────

    // background audio playback is unreliable on most OEM skins unless the user
    // exempts the app from battery optimization — prompt once, then never again
    private fun requestBatteryOptimizationExemption() {
        val prefs = getSharedPreferences("meowify_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_opt_prompted", false)) return
        prefs.edit().putBoolean("battery_opt_prompted", true).apply()

        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "could not request battery optimization exemption", e)
        }
    }

    private fun requestNeededPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    // ── file import helpers ────────────────────────────────────────────────────

    private fun copyToCache(uri: Uri): File? = try {
        val name = getDisplayName(uri) ?: "audio_${System.currentTimeMillis()}"
        val ext  = name.substringAfterLast('.', "mp3").lowercase().take(5)
        val tmp  = File(cacheDir, "import_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        }
        tmp
    } catch (e: Exception) {
        Log.e(TAG, "copyToCache failed for $uri", e)
        null
    }

    private fun getDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }
    }

    // ── JS → Kotlin bridge ─────────────────────────────────────────────────────

    inner class MeowifyBridge {

        @JavascriptInterface
        fun isAndroid(): String = "true"

        @JavascriptInterface
        fun getFilesDir(): String = filesDir.absolutePath

        @JavascriptInterface
        fun shareFile(path: String) {
            runOnUiThread {
                val file = File(path)
                if (!file.exists()) {
                    Log.w(TAG, "shareFile: file not found: $path")
                    return@runOnUiThread
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity, "${packageName}.provider", file
                )
                val intent = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "audio/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "share via"
                )
                try { startActivity(intent) }
                catch (e: Exception) { Log.e(TAG, "shareFile failed", e) }
            }
        }

        @JavascriptInterface
        fun reload() = runOnUiThread { webView?.reload() }

        // called from meowify JS whenever playback state changes so the
        // android media session / lock screen / notification controls stay accurate
        @JavascriptInterface
        fun onPlaybackState(
            playing: Boolean,
            title: String,
            artist: String,
            album: String,
            coverPath: String,
            durationSec: Double,
            positionSec: Double,
            shuffle: Boolean,
            repeat: String
        ) {
            val messenger = serviceMessenger ?: run {
                Log.w(TAG, "onPlaybackState: not bound to ServerService yet, dropping")
                return
            }
            val data = Bundle().apply {
                putBoolean(ServerService.KEY_PLAYING, playing)
                putString(ServerService.KEY_TITLE, title)
                putString(ServerService.KEY_ARTIST, artist)
                putString(ServerService.KEY_ALBUM, album)
                putString(ServerService.KEY_COVER_PATH, coverPath)
                putDouble(ServerService.KEY_DURATION_SEC, durationSec)
                putDouble(ServerService.KEY_POSITION_SEC, positionSec)
                putBoolean(ServerService.KEY_SHUFFLE, shuffle)
                putString(ServerService.KEY_REPEAT, repeat)
            }
            val msg = Message.obtain(null, ServerService.MSG_PLAYBACK_STATE).apply { this.data = data }
            try {
                messenger.send(msg)
            } catch (e: RemoteException) {
                Log.e(TAG, "onPlaybackState: send to ServerService failed", e)
            }
        }
    }
}
