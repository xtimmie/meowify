# meowify android

native android wrapper for meowify — runs the flask server in a foreground service, hosts the UI in a WebView.

## requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- NDK not required (arm64 only, chaquopy handles the python runtime)
- internet connection at build time (chaquopy downloads Python + pip packages)

## quick start

```bash
# from the meowify-android/ directory:
chmod +x setup.sh
./setup.sh
```

the script:
1. copies `meowify.py` from the parent directory into `assets/`
2. downloads a static arm64 ffmpeg binary into `assets/`
3. copies your `motionui/` folder into `assets/`

then open the project in Android Studio, let Gradle sync, and run.

## project structure

```
app/src/main/
├── assets/
│   ├── meowify.py          ← copied by setup.sh (your unmodified server)
│   ├── android_patch.py    ← monkey-patches meowify for android at runtime
│   ├── ffmpeg              ← static arm64 binary, downloaded by setup.sh
│   └── motionui/           ← copied by setup.sh
├── java/com/xtim/meowify/
│   ├── MeowifyApp.kt       ← application class, notification channels
│   ├── ServerService.kt    ← foreground service running flask via chaquopy
│   └── MainActivity.kt     ← webview host, file picker bridge
└── res/
```

## how it works

1. `ServerService` starts as a foreground service when the app opens
2. it extracts `ffmpeg`, `motionui/`, and `meowify.py` from assets to `filesDir`
3. it finds a free port and starts the flask server via chaquopy (python embedded in the APK)
4. `android_patch.py` stubs out unavailable C-extension packages (miniaudio/numpy) so loudness analysis gracefully no-ops
5. a yt-dlp shim script is written to a bin/ folder so `subprocess(['yt-dlp', ...])` calls work — they invoke `python -m yt_dlp` under the hood
6. `MainActivity` polls the port, then loads `http://127.0.0.1:<port>` in a WebView
7. the WebView has `mediaPlaybackRequiresUserGesture = false` so audio plays without a tap

## what's different from desktop

| feature | android |
|---|---|
| loudness analysis (LUFS) | ✗ skipped (no numpy/miniaudio) |
| gapless / crossfade | ✓ works via Web Audio API in WebView |
| media keys / lockscreen | ✓ via WebView's built-in `navigator.mediaSession` |
| yt-dlp downloads | ✓ runs as `python -m yt_dlp` |
| ffmpeg transcoding | ✓ static arm64 binary bundled |
| file import | ✓ Android file picker bridge |
| cover art | ✓ |
| genius metadata | ✓ |
| playlists | ✓ |

## building a release APK

1. `Build → Generate Signed Bundle / APK`
2. create or select a keystore
3. select `release` build variant
4. the APK will be at `app/release/app-release.apk`

install on device:
```bash
adb install app/release/app-release.apk
```

## data location

all music, covers, and the sqlite database live in:
`/data/data/com.xtim.meowify/files/meowify/`

accessible via Android Studio's Device File Explorer or `adb pull`.

## troubleshooting

**server takes >30s to start on first launch**  
chaquopy initialises its python environment on first run — subsequent starts are fast.

**yt-dlp fails with "ffmpeg not found"**  
the ffmpeg binary is extracted from assets on first run. if it fails, check logcat for `MeowifyServer` tag.

**WebView shows blank / ERR_CONNECTION_REFUSED**  
the server is still starting. the loading screen should handle this, but if it persists, force-close and reopen the app.
