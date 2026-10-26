#!/usr/bin/env bash
# meowify-android setup script
# Run from the meowify-android/ directory before building.
# Requires: curl, unzip, Android SDK

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSETS="$SCRIPT_DIR/app/src/main/assets"
mkdir -p "$ASSETS"

echo "── meowify android setup ──"
echo

# ── 1. copy meowify.py ────────────────────────────────────────────────────────
echo "[1/3] looking for meowify.py..."
if [ -f "$SCRIPT_DIR/../meowify.py" ]; then
    cp "$SCRIPT_DIR/../meowify.py" "$ASSETS/meowify.py"
    echo "  copied ../meowify.py → assets/meowify.py"
elif [ -f "$SCRIPT_DIR/meowify.py" ]; then
    cp "$SCRIPT_DIR/meowify.py" "$ASSETS/meowify.py"
    echo "  copied meowify.py → assets/meowify.py"
else
    echo "  !! meowify.py not found. Place it in the meowify-android/ folder or its parent."
    echo "     Expected: $SCRIPT_DIR/../meowify.py"
    exit 1
fi

if [ -f "$SCRIPT_DIR/../android_patch.py" ]; then
    cp "$SCRIPT_DIR/../android_patch.py" "$ASSETS/android_patch.py"
    echo "  copied ../android_patch.py → assets/android_patch.py"
else
    echo "  !! android_patch.py not found at $SCRIPT_DIR/../android_patch.py"
    exit 1
fi

# ── 2. download static ffmpeg arm64 ──────────────────────────────────────────
echo
echo "[2/3] downloading static ffmpeg arm64..."

FFMPEG_URL="https://github.com/eugeneware/ffmpeg-static/releases/download/b6.0/ffmpeg-linux-arm64"
FFMPEG_DEST="$ASSETS/ffmpeg"

if [ -f "$FFMPEG_DEST" ] && [ "$(stat -c%s "$FFMPEG_DEST" 2>/dev/null || stat -f%z "$FFMPEG_DEST")" -gt 1000000 ]; then
    echo "  ffmpeg already present ($(du -h "$FFMPEG_DEST" | cut -f1)), skipping download"
else
    echo "  downloading from: $FFMPEG_URL"
    echo "  this may take a minute (~40MB)..."
    curl -L --progress-bar -o "$FFMPEG_DEST" "$FFMPEG_URL"
    chmod +x "$FFMPEG_DEST"
    SIZE=$(du -h "$FFMPEG_DEST" | cut -f1)
    echo "  ffmpeg downloaded ($SIZE)"
fi

# ── 3. motionui check ────────────────────────────────────────────────────────
echo
echo "[3/3] checking motionui..."
MU_DEST="$ASSETS/motionui"
rm -rf "$MU_DEST"

if [ -d "$MU_DEST" ] && [ "$(ls -A "$MU_DEST" 2>/dev/null | wc -l)" -gt 0 ]; then
    echo "  motionui/ already in assets/ ✓"
else
    # try to find motionui adjacent to meowify.py
    CANDIDATES=(
        "$SCRIPT_DIR/../motionui"
        "$SCRIPT_DIR/motionui"
        "$(dirname "$(realpath "$ASSETS/meowify.py" 2>/dev/null || echo /dev/null)")/motionui"
    )
    FOUND=""
    for c in "${CANDIDATES[@]}"; do
        if [ -d "$c" ]; then
            FOUND="$c"
            break
        fi
    done

    if [ -n "$FOUND" ]; then
        mkdir -p "$MU_DEST"
        cp -r "$FOUND/." "$MU_DEST/"
        echo "  copied motionui from $FOUND ✓"
    else
        echo "  !! motionui/ not found. Copy your motionui folder to:"
        echo "     $MU_DEST"
        echo "  The app will still build but meowify's UI may not load correctly."
    fi
fi

# ── done ─────────────────────────────────────────────────────────────────────
echo
echo "── setup complete ──"
echo
echo "next steps:"
echo "  1. open meowify-android/ in Android Studio"
echo "  2. let Gradle sync (chaquopy will download Python 3.11 + pip packages)"
echo "  3. Build → Generate Signed APK  (or just Run for debug)"
echo
echo "note: first gradle sync downloads ~200MB of chaquopy Python runtime."
echo "      pip install runs at build time — needs network."
echo
