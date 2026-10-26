# building meowify for android

step-by-step from zero to sideloaded APK.

---

## prerequisites

### 1. install Android Studio

download **Android Studio Hedgehog (2023.1.1)** or newer from:
https://developer.android.com/studio

during installation, let it install the default SDK components.

### 2. install required SDK components

open Android Studio → **SDK Manager** (wrench icon or File → Settings → Appearance & Behavior → System Settings → Android SDK)

under **SDK Platforms** tab, check:
- **Android 14 (API 34)** ✓

under **SDK Tools** tab, check:
- **Android SDK Build-Tools 34** ✓
- **Android SDK Platform-Tools** ✓
- **Android Emulator** (optional, only if testing without a real device)

click **Apply** and let it download.

### 3. Java 17

Android Studio bundles its own JDK 17 — you don't need to install Java separately. if you get JDK errors, go to:
File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK → select **"Android Studio JDK"**

---

## setup (run once before opening the project)

### Windows

```powershell
# open PowerShell in the meowify-android\ folder, then:
powershell -ExecutionPolicy Bypass -File setup.ps1
```

or right-click `setup.ps1` → **"Run with PowerShell"**

### macOS / Linux

```bash
chmod +x setup.sh
./setup.sh
```

the script copies `meowify.py`, downloads the arm64 ffmpeg binary (~40 MB), and copies `motionui/` into `assets/`. run it from the `meowify-android/` directory.

**your folder layout should look like this before running setup:**
```
meowify.py              ← your server file
motionui/               ← your motionui folder
meowify-android/        ← this project
    setup.ps1
    setup.sh
    app/
    ...
```

---

## opening the project in Android Studio

1. open Android Studio
2. click **"Open"** (not "New Project")
3. navigate to and select the **`meowify-android`** folder
4. click **OK**

Android Studio will detect it as a Gradle project and start syncing.

---

## gradle sync (the slow first-time step)

the first sync downloads:
- chaquopy's Python 3.11 runtime (~80 MB)
- pip packages: flask, yt-dlp, mutagen, ytmusicapi (~120 MB)
- standard gradle dependencies (~50 MB)

**this takes 5–15 minutes** depending on your internet. subsequent syncs are fast (seconds).

you'll see a progress bar at the bottom of Android Studio. wait until it says **"Gradle sync finished"** in the status bar.

if sync fails, the most common causes:
- no internet connection
- corporate proxy blocking chaquopy's maven repo (`https://chaquo.com/maven`) — add proxy settings in File → Settings → Appearance & Behavior → System Settings → HTTP Proxy
- outdated Android Studio — update to Hedgehog or newer

---

## building the debug APK (for testing)

once sync is done:

**option A — run directly on a connected device:**
1. plug in your android phone via USB
2. on the phone: Settings → Developer Options → enable **USB Debugging**
   (if Developer Options isn't visible: Settings → About Phone → tap "Build Number" 7 times)
3. in Android Studio, select your device in the toolbar dropdown
4. click the **▶ Run** button (or Shift+F10)
5. Android Studio builds and installs the APK automatically

**option B — build APK file to sideload manually:**
1. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. wait for the build (~1–3 minutes first time, faster after)
3. click **"locate"** in the notification that appears, or find the file at:
   `meowify-android\app\build\outputs\apk\debug\app-debug.apk`
4. copy to your phone and open it (you need "Install from unknown sources" enabled)

---

## enabling "install from unknown sources" on your phone

this is required for sideloading (only needs to be done once per install method):

**Android 8+:**
Settings → Apps → Special App Access → Install Unknown Apps → find your file manager or browser → allow

**Samsung One UI:**
Settings → Biometrics and Security → Install Unknown Apps

---

## building a release APK (smaller, faster, no debug info)

for a proper release build you need a signing keystore.

### step 1: create a keystore (one-time)

**Build → Generate Signed Bundle / APK**

1. select **APK**
2. click **"Create new..."**
3. fill in:
   - **Key store path**: choose somewhere safe (e.g. `C:\keys\meowify.jks`) — **back this up**
   - **Password**: set a strong password
   - **Key alias**: `meowify`
   - **Key password**: same or different
   - **Validity**: 25 years
   - **First and last name**: anything
4. click **OK**

### step 2: build the release APK

1. **Build → Generate Signed Bundle / APK**
2. select **APK**
3. select your keystore, enter passwords, select alias
4. click **Next**
5. select **release** build variant
6. check **V1 (Jar Signature)** and **V2 (Full APK Signature)**
7. click **Finish**

the signed APK will be at:
`meowify-android\app\release\app-release.apk`

### step 3: install on device

```bash
# via adb (fastest):
adb install app\release\app-release.apk

# or copy to phone and open the file
```

---

## first launch behaviour

on first launch the app will:
1. show "starting server…" for **10–30 seconds** — this is chaquopy initialising python for the first time
2. extract ffmpeg and motionui from the APK to internal storage
3. install the yt-dlp shim script
4. start the flask server and load the UI

**subsequent launches are much faster** (~2–4 seconds) because the extraction is skipped if files already exist.

---

## updating meowify.py

when you change `meowify.py` on desktop:

1. run setup again (it re-copies the file):
   ```powershell
   powershell -ExecutionPolicy Bypass -File setup.ps1
   ```
2. rebuild in Android Studio (**Build → Rebuild Project**)
3. reinstall on device

the app detects file changes by version code — if you bump `versionCode` in `app/build.gradle` it will re-extract all assets automatically.

---

## logcat — reading server logs

the flask server logs to Android's logcat. to see them:

1. in Android Studio, open **Logcat** tab (bottom toolbar, or View → Tool Windows → Logcat)
2. filter by tag: type `MeowifyServer` in the filter box
3. you'll see all flask output, yt-dlp download progress, genius API calls, etc.

filter tags:
- `MeowifyServer` — server startup, port, errors
- `MeowifyMain` — WebView, file picker
- `WebConsole` — javascript console.log output from meowify's frontend

---

## common issues

**"SDK location not found"**
File → Project Structure → SDK Location → set Android SDK location (usually `C:\Users\you\AppData\Local\Android\Sdk`)

**"Minimum supported Gradle version is X"**
in `gradle/wrapper/gradle-wrapper.properties`, update the distributionUrl. Android Studio will usually offer to do this automatically.

**chaquopy pip install fails during sync**
check internet. if behind a proxy, configure it in Android Studio settings. the chaquopy pip cache is at `~/.gradle/caches/` — delete it and re-sync if corrupted.

**app crashes on launch (blank screen)**
open Logcat, filter `MeowifyServer`, look for the python traceback. most common: meowify.py not in assets (re-run setup.ps1).

**"INSTALL_FAILED_UPDATE_INCOMPATIBLE"**
uninstall the old version first: `adb uninstall com.xtim.meowify` or uninstall via Settings on the phone.
