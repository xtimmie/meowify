#!/usr/bin/env bash
set -euo pipefail

REPO="xtimmie/meowify"
ROOT="$HOME/.local/lib/meowify"
MU_DIR="$HOME/.local/lib/motionui"
LATEST="https://github.com/$REPO/releases/latest/download"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

step() { printf '==> %s\n' "$*"; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl is required"

SUDO=""
if [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1; then SUDO="sudo"; fi

install_pkgs() {
  local what="$1"
  if command -v apt-get >/dev/null 2>&1; then
    case "$what" in
      python) $SUDO apt-get update -y && $SUDO apt-get install -y python3 python3-venv python3-pip ;;
      ffmpeg) $SUDO apt-get update -y && $SUDO apt-get install -y ffmpeg ;;
    esac
  elif command -v dnf >/dev/null 2>&1; then
    case "$what" in
      python) $SUDO dnf install -y python3 python3-pip ;;
      ffmpeg) $SUDO dnf install -y ffmpeg-free ;;
    esac
  elif command -v pacman >/dev/null 2>&1; then
    case "$what" in
      python) $SUDO pacman -S --noconfirm python python-pip ;;
      ffmpeg) $SUDO pacman -S --noconfirm ffmpeg ;;
    esac
  else
    return 1
  fi
}

py_ok() {
  "$1" -c 'import sys,venv,ensurepip; sys.exit(0 if sys.version_info >= (3,10) else 1)' >/dev/null 2>&1
}

find_python() {
  local c p
  for c in python3 python; do
    p="$(command -v "$c" 2>/dev/null || true)"
    if [ -n "$p" ] && py_ok "$p"; then echo "$p"; return 0; fi
  done
  return 1
}

PY="$(find_python || true)"
if [ -z "$PY" ]; then
  step "installing python 3.10+ with venv support"
  install_pkgs python || die "could not install python automatically, install python 3.10+ with venv and re-run"
  PY="$(find_python || true)"
  [ -n "$PY" ] || die "python still not usable after install"
fi
step "using python at $PY"

if ! command -v ffmpeg >/dev/null 2>&1; then
  step "installing ffmpeg"
  install_pkgs ffmpeg || printf 'warning: install ffmpeg yourself, downloads and conversion need it\n' >&2
fi

fetch()  { curl -fsSL --retry 3 -o "$2" "$1"; }
sha_of() { sha256sum "$1" | cut -d' ' -f1; }
mget()   { "$PY" -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$TMP/manifest.json" "$1"; }
mhash()  { "$PY" -c 'import json,sys; print(json.load(open(sys.argv[1]))["files"][sys.argv[2]])' "$TMP/manifest.json" "$1"; }

step "fetching latest release info"
fetch "$LATEST/manifest.json" "$TMP/manifest.json"
TAG="$(mget tag)"
VERSION="$(mget version)"
MU_TAG="$(mget motionui)"
BASE="https://github.com/$REPO/releases/download/$TAG"

mkdir -p "$ROOT"
for name in meowify.py requirements.txt launcher.pyw; do
  step "downloading $name"
  fetch "$BASE/$name" "$TMP/$name"
  want="$(mhash "$name")"
  [ "$(sha_of "$TMP/$name")" = "$want" ] || die "$name failed the hash check"
done
for name in meowify.py requirements.txt launcher.pyw; do
  cp "$TMP/$name" "$ROOT/$name"
done

if ! "$ROOT/venv/bin/python" -c 'pass' >/dev/null 2>&1; then
  step "creating virtual environment"
  rm -rf "$ROOT/venv"
  "$PY" -m venv "$ROOT/venv"
fi
step "installing python packages (this takes a few minutes)"
"$ROOT/venv/bin/python" -m pip install --upgrade pip --disable-pip-version-check
"$ROOT/venv/bin/python" -m pip install -r "$ROOT/requirements.txt" --disable-pip-version-check

step "installing motionui $MU_TAG"
fetch "https://github.com/xtimmie/motionui/archive/refs/tags/$MU_TAG.zip" "$TMP/mu.zip"
"$PY" - "$TMP/mu.zip" "$TMP/mu" <<'EOF'
import sys, zipfile
zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])
EOF
SRC="$(find "$TMP/mu" -mindepth 1 -maxdepth 1 -type d | head -n1)"
[ -f "$SRC/motionui.js" ] || die "motionui download looks wrong"
rm -rf "$MU_DIR"
mkdir -p "$(dirname "$MU_DIR")"
cp -r "$SRC" "$MU_DIR"

"$PY" - "$ROOT/.install.json" "$VERSION" "$TAG" "$MU_TAG" <<'EOF'
import json, sys
json.dump({"version": sys.argv[2], "tag": sys.argv[3], "motionui": sys.argv[4]}, open(sys.argv[1], "w"))
EOF

step "creating launcher"
mkdir -p "$HOME/.local/bin" "$HOME/.local/share/applications"
cat > "$HOME/.local/bin/meowify" <<EOF
#!/usr/bin/env bash
exec "$ROOT/venv/bin/python" "$ROOT/launcher.pyw" "\$@"
EOF
chmod +x "$HOME/.local/bin/meowify"
cat > "$HOME/.local/share/applications/meowify.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=meowify
Exec=$HOME/.local/bin/meowify
Terminal=false
Categories=AudioVideo;Audio;Player;
EOF

printf '\nmeowify %s installed to %s\n' "$VERSION" "$ROOT"
printf 'run it with: meowify (make sure ~/.local/bin is on your PATH)\n'