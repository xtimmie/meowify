"""
android_patch.py
Patches meowify for Android. Loaded by ServerService before anything else.
Key constraint: meowify.py runs module-level code (load_cfg, _HOME, etc)
at import time, so we must redirect paths BEFORE importing it.
"""

import os
import sys

# ── 1. get data dir from env (set by ServerService before calling us) ─────────
_data_dir = os.environ.get("MEOWIFY_DATA_DIR", "")

def _ensure_ota_env():
    # android_patch.py lives in filesDir/meowify (bundled) or filesDir/meowify/ota (overlay)
    here = os.path.dirname(os.path.abspath(__file__))
    ota = here if os.path.basename(here) == "ota" else os.path.join(here, "ota")
    if not os.environ.get("MEOWIFY_OTA_DIR"):
        os.environ["MEOWIFY_OTA_DIR"] = ota
    os.makedirs(os.environ["MEOWIFY_OTA_DIR"], exist_ok=True)
    return os.environ["MEOWIFY_OTA_DIR"]
if _data_dir:
    os.makedirs(_data_dir, exist_ok=True)

# ── 2. patch pathlib.Path.home() BEFORE importing meowify ────────────────────
# meowify.py does _HOME = Path.home() / ".local/share/meowify" at module level.
# We redirect home() so it returns our data dir's parent, making _HOME resolve
# to the right place without touching meowify.py at all.
import pathlib as _pathlib

_orig_home = _pathlib.Path.home

def _android_home():
    if _data_dir:
        # return parent of data_dir so that parent / ".local/share/meowify" works
        # but meowify uses Path.home() / ".local" / "share" / "meowify"
        # easier: just return data_dir directly and override _HOME after import
        return _orig_home()
    return _orig_home()

# Actually the cleanest approach: patch _HOME after import via sys.modules trick.
# Instead, pre-create the settings.json path and rely on _patch_paths() post-import.
# ─────────────────────────────────────────────────────────────────────────────

# ── 3. stub unavailable C-extension packages BEFORE any import ────────────────
# meowify does lazy imports of these inside functions, but if they raise
# ImportError the function crashes. Stub them all as no-op fake modules.

class _FakeModule:
    """Returns itself for any attribute access or call — safe no-op sink."""
    def __getattr__(self, name):
        return _FakeModule()
    def __call__(self, *a, **kw):
        return _FakeModule()
    def __iter__(self):
        return iter([])
    def __bool__(self):
        return False
    def __float__(self):
        return 0.0
    def __int__(self):
        return 0
    def __len__(self):
        return 0
    # numpy array ops meowify uses
    def __gt__(self, other): return _FakeModule()
    def __mul__(self, other): return _FakeModule()
    def __add__(self, other): return _FakeModule()
    def __sub__(self, other): return _FakeModule()
    def __truediv__(self, other): return _FakeModule()
    def __pow__(self, other): return _FakeModule()
    def reshape(self, *a, **kw): return _FakeModule()
    def mean(self, *a, **kw): return 0.0

# build a more complete numpy stub
_np = _FakeModule()
_np.float32 = float
_np.frombuffer = lambda *a, **kw: _FakeModule()
_np.errstate = lambda **kw: __import__('contextlib').nullcontext()
_np.log10 = lambda x: 0.0
_np.mean = lambda x, **kw: 0.0
_np.any = lambda x: False
_np.ones_like = lambda x, **kw: _FakeModule()
_np.pi = 3.14159265358979
_np.tan = lambda x: 0.0
_np.sqrt = lambda x: 0.0

class _NpLib:
    class stride_tricks:
        @staticmethod
        def as_strided(*a, **kw): return _FakeModule()
_np.lib = _NpLib()

# register all numpy sub-paths that might be imported
for _mod_name in ("numpy", "numpy.lib", "numpy.lib.stride_tricks",
                  "miniaudio", "scipy", "scipy.signal"):
    sys.modules.setdefault(_mod_name, _np)  # type: ignore

# ── 4. install yt-dlp shim so subprocess(['yt-dlp', ...]) works ─────────────── (dropped)

# ── 5. patch meowify module after import ──────────────────────────────────────

def _patch_paths(meowify_module):
    """Redirect _HOME and related path constants to android data dir."""
    from pathlib import Path
    import shutil

    android_home = Path(_data_dir) if _data_dir else Path("/data/local/tmp/meowify")
    android_home.mkdir(parents=True, exist_ok=True)

    # migrate settings from the wrong path (Path.home()/.local/share/meowify) if present
    wrong_cfg = meowify_module._SCFG
    meowify_module._HOME = android_home
    meowify_module._SCFG = android_home / "settings.json"

    if wrong_cfg.exists() and not meowify_module._SCFG.exists():
        try:
            shutil.copy2(wrong_cfg, meowify_module._SCFG)
            import sys as _s; _s.__stderr__.write(f'[patch] migrated cfg from {wrong_cfg}\n'); _s.__stderr__.flush()
        except Exception as e:
            import sys as _s; _s.__stderr__.write(f'[patch] migrate failed: {e}\n'); _s.__stderr__.flush()

    # reload CFG now that _HOME points to the right place
    meowify_module.CFG = meowify_module.load_cfg()
    print(f'[patch] _SCFG now={meowify_module._SCFG} token={bool(meowify_module.CFG.get("dropbox_refresh_token"))} cfg_id={id(meowify_module.CFG)}')

    # point MOTIONUI_DIR to the extracted location
    mu_marker = android_home / ".motionui_dir"
    if mu_marker.exists():
        meowify_module.MOTIONUI_DIR = mu_marker.read_text().strip()

def _patch_loudness(meowify_module):
    """Replace all loudness analysis functions with no-ops (no numpy/miniaudio)."""
    _noop = lambda *a, **kw: None
    meowify_module._analyze_loudness        = _noop
    meowify_module._analyze_loudness_numpy  = _noop
    meowify_module._decode_pcm              = _noop
    meowify_module._kweight                 = _noop
    meowify_module._import_numpy            = lambda: sys.modules["numpy"]
    meowify_module._import_miniaudio        = lambda: sys.modules["miniaudio"]

def _patch_ffmpeg():
    """Ensure ffmpeg binary dir is in PATH."""
    import sys as _sys
    ffmpeg = os.environ.get("MEOWIFY_FFMPEG", "")
    if ffmpeg and os.path.exists(ffmpeg):
        ff_dir = os.path.dirname(ffmpeg)
        current = os.environ.get("PATH", "")
        if ff_dir not in current:
            os.environ["PATH"] = f"{ff_dir}:{current}"
        executable = os.access(ffmpeg, os.X_OK)
        _sys.__stderr__.write(f'[ffmpeg] path={ffmpeg} exists=True executable={executable}\n')
        _sys.__stderr__.flush()
    else:
        _sys.__stderr__.write(f'[ffmpeg] NOT FOUND: env={ffmpeg!r} exists={os.path.exists(ffmpeg) if ffmpeg else False}\n')
        _sys.__stderr__.flush()


def _patch_subp(meowify_module):
    """
    meowify uses _subp/_subrun which call subprocess.Popen/run.
    On Android: strip ffmpeg post-processing flags from yt-dlp args,
    run yt_dlp in-process, and block any ffmpeg subprocess fork entirely.
    """
    import subprocess
    import sys
    import threading

    _FFMPEG_BINS = {'ffmpeg', 'ffprobe', 'avconv', 'avprobe'}

    # resolve ffmpeg availability once — set up by _patch_ffmpeg() before this runs
    _ffmpeg_path = os.environ.get("MEOWIFY_FFMPEG", "")
    _ffmpeg_available = bool(_ffmpeg_path and os.path.exists(_ffmpeg_path)
                              and os.access(_ffmpeg_path, os.X_OK))

    def _is_ffmpeg(args):
        if not args:
            return False
        import os
        return os.path.basename(str(args[0])) in _FFMPEG_BINS

    def _is_ytdlp(args):
        if not args:
            return False
        first = args[0] if isinstance(args, (list, tuple)) else args
        return str(first).endswith('yt-dlp') or str(first) == 'yt-dlp'

    # flags that need ffmpeg post-processing — strip these on android
    # only when ffmpeg is unavailable (fallback path)
    _FFMPEG_FLAGS_WITH_VALUE = {
        '--audio-format', '--audio-quality', '--convert-thumbnails',
        '--embed-thumbnail', '--parse-metadata',
    }
    _FFMPEG_FLAGS_SOLO = {'-x', '--extract-audio', '--embed-chapters',
                          '--embed-metadata', '--add-metadata', '--xattrs'}

    def _strip_ffmpeg_flags(args):
        """Remove flags that require ffmpeg post-processing, keep everything else.
        Only used when ffmpeg is unavailable on this device."""
        if _ffmpeg_available:
            return list(args)
        new_args = [args[0]]
        skip_next = False
        for a in args[1:]:
            if skip_next:
                skip_next = False
                continue
            if a in _FFMPEG_FLAGS_WITH_VALUE:
                skip_next = True
                continue
            if a in _FFMPEG_FLAGS_SOLO:
                continue
            new_args.append(a)
        # only inject -f if the caller didn't already specify one
        if '-f' not in new_args and '--format' not in new_args:
            new_args += ['-f', 'bestaudio/best']
        new_args += ['--no-post-overwrites']
        return new_args

    def _ytdlp_popen(args, **kw):
        """Run yt_dlp in-process via YoutubeDL API, return a fake Popen.
        Progress lines ('[download] XX%') are written into the read pipe so
        meowify's stdout-parsing loop works unchanged. No global stream redirect."""
        import os as _os
        import yt_dlp as _ytdlp_mod
        ytdlp_args = list(args)[1:]

        r_fd, w_fd = _os.pipe()
        r_file = _os.fdopen(r_fd, 'r', errors='replace')
        w_file = _os.fdopen(w_fd, 'w', errors='replace')
        result_holder = [0]

        # parse the flat arg list into YoutubeDL opts
        def _build_ydl_opts(ytargs):
            opts = {
                'quiet':            True,
                'no_warnings':      True,
                'noprogress':       False,   # we want progress hooks to fire
                'noplaylist':       True,
                'outtmpl':          None,    # filled below
                'concurrent_fragment_downloads': 4,
                'postprocessors':   [],      # filled below if ffmpeg available
            }
            if _ffmpeg_available:
                opts['ffmpeg_location'] = os.path.dirname(_ffmpeg_path)

            _audio_format = None
            _audio_quality = None
            _want_embed_metadata = False
            _want_embed_thumbnail = False
            _want_convert_thumbnail = None

            i = 0
            url = None
            while i < len(ytargs):
                a = ytargs[i]
                if a in ('-o', '--output') and i + 1 < len(ytargs):
                    opts['outtmpl'] = ytargs[i + 1]; i += 2; continue
                if a in ('--concurrent-fragments',) and i + 1 < len(ytargs):
                    opts['concurrent_fragment_downloads'] = int(ytargs[i + 1]); i += 2; continue
                if a in ('--sleep-requests',) and i + 1 < len(ytargs):
                    opts['sleep_interval_requests'] = float(ytargs[i + 1]); i += 2; continue
                if a in ('-f', '--format') and i + 1 < len(ytargs):
                    opts['format'] = ytargs[i + 1]; i += 2; continue
                if a in ('--extractor-args',) and i + 1 < len(ytargs):
                    _ea = {}
                    for _part in ytargs[i + 1].split(';'):
                        if ':' not in _part:
                            continue
                        _extractor, _kvs = _part.split(':', 1)
                        _kvmap = {}
                        for _kv in _kvs.split(';'):
                            if '=' not in _kv:
                                continue
                            _k, _v = _kv.split('=', 1)
                            _kvmap[_k] = _v.split(',')
                        _ea[_extractor] = _kvmap
                    opts['extractor_args'] = _ea
                    i += 2; continue
                if a in ('--write-info-json', '--write-info_json'):
                    opts['writeinfojson'] = True; i += 1; continue
                if a in ('--write-thumbnail',):
                    opts['writethumbnail'] = True; i += 1; continue
                if a in ('--no-playlist',):
                    opts['noplaylist'] = True; i += 1; continue
                if a in ('-x', '--extract-audio'):
                    i += 1; continue
                if a in ('--audio-format',) and i + 1 < len(ytargs):
                    _audio_format = ytargs[i + 1]; i += 2; continue
                if a in ('--audio-quality',) and i + 1 < len(ytargs):
                    _audio_quality = ytargs[i + 1]; i += 2; continue
                if a in ('--embed-metadata', '--add-metadata'):
                    _want_embed_metadata = True; i += 1; continue
                if a in ('--embed-thumbnail',):
                    _want_embed_thumbnail = True; i += 1; continue
                if a in ('--convert-thumbnails',) and i + 1 < len(ytargs):
                    _want_convert_thumbnail = ytargs[i + 1]; i += 2; continue
                if a in ('--newline', '--no-post-overwrites', '--no-warnings'):
                    i += 1; continue
                if not a.startswith('-'):
                    url = a
                i += 1

            if _audio_format and 'format' not in opts:
                opts['format'] = 'bestaudio/best'
            if _ffmpeg_available and _audio_format:
                pps = opts['postprocessors']
                pps.append({
                    'key': 'FFmpegExtractAudio',
                    'preferredcodec': _audio_format,
                    'preferredquality': _audio_quality or '5',
                })
                if _want_embed_metadata:
                    pps.append({'key': 'FFmpegMetadata'})
                if _want_embed_thumbnail:
                    pps.append({'key': 'EmbedThumbnail'})
                if _want_convert_thumbnail:
                    pps.append({'key': 'FFmpegThumbnailsConvertor',
                                 'format': _want_convert_thumbnail})

            return opts, url

        opts, url = _build_ydl_opts(ytdlp_args)
        if opts.get('outtmpl') is None:
            opts['outtmpl'] = '%(id)s.%(ext)s'

        def _progress_hook(d):
            """Translate yt_dlp progress dict → '[download] XX%' line for meowify."""
            if d.get('status') == 'downloading':
                pct = d.get('downloaded_bytes', 0)
                total = d.get('total_bytes') or d.get('total_bytes_estimate', 0)
                if total:
                    w_file.write(f'[download] {pct / total * 100:.1f}%\n')
                    w_file.flush()

        opts['progress_hooks'] = [_progress_hook]
        opts['no_warnings'] = False

        class _PipeLogger:
            def debug(self, msg): pass
            def info(self, msg): pass
            def warning(self, msg):
                try: w_file.write(str(msg) + '\n')
                except Exception: pass
            def error(self, msg):
                try: w_file.write(str(msg) + '\n')
                except Exception: pass

        opts['logger'] = _PipeLogger()

        def run():
            try:
                w_file.write(f'[shim] ffmpeg_available={_ffmpeg_available} path={_ffmpeg_path!r} postprocessors={[p["key"] for p in opts["postprocessors"]]}\n')
                pass
                if url:
                    with _ytdlp_mod.YoutubeDL(opts) as ydl:
                        rc = ydl.download([url])
                        result_holder[0] = rc or 0
                else:
                    result_holder[0] = 1
                    w_file.write('yt-dlp error: no URL found in args\n')
            except Exception as e:
                result_holder[0] = 1
                w_file.write(f'yt-dlp error: {e}\n')
            finally:
                w_file.close()

        t = threading.Thread(target=run, daemon=True)
        t.start()

        class FakePopen:
            def __init__(self):
                self.stdout = r_file
                self.returncode = None
            def wait(self):
                t.join()
                self.returncode = result_holder[0]
                sys.__stderr__.write(f'[ytdlp] exit={self.returncode}\n')
                sys.__stderr__.flush()
                return self.returncode
            def poll(self):
                if t.is_alive():
                    return None
                self.returncode = result_holder[0]
                return self.returncode
            def communicate(self, input=None):
                out = r_file.read()
                t.join()
                self.returncode = result_holder[0]
                sys.__stderr__.write(f'[ytdlp] communicate exit={self.returncode} out_len={len(out)}\n')
                sys.__stderr__.flush()
                return out, ''

        return FakePopen()

    def _android_subp(args, **kw):
        import sys as _sys
        _sys.__stderr__.write(f'[subp] called: {list(args[:3])}\n')
        _sys.__stderr__.flush()
        kw.pop('creationflags', None)
        if _is_ytdlp(args):
            return _ytdlp_popen(_strip_ffmpeg_flags(list(args)), **kw)
        kw.setdefault('close_fds', True)
        return subprocess.Popen(args, **kw)

    def _android_subrun(args, **kw):
        """All _subrun yt-dlp calls are metadata-only (--dump-json / -J).
        Use YoutubeDL.extract_info(download=False) directly — no stream redirect."""
        kw.pop('creationflags', None)
        kw.setdefault('close_fds', True)
        if not _is_ytdlp(args):
            return subprocess.run(args, **kw)

        import json as _json
        import yt_dlp as _yt

        ytdlp_args = list(args)[1:]
        url = next((a for a in ytdlp_args if not a.startswith('-')), None)

        class _CP:
            def __init__(self, rc, out, err=''):
                self.returncode = rc
                self.stdout     = out
                self.stderr     = err
            def check_returncode(self):
                if self.returncode:
                    raise subprocess.CalledProcessError(self.returncode, args)

        if not url:
            sys.__stderr__.write('[subrun ytdlp] no url found in args\n')
            sys.__stderr__.flush()
            return _CP(1, '', 'no url')

        try:
            ydl_opts = {
                'quiet':       True,
                'no_warnings': True,
                'noplaylist':  True,
            }
            # pass format through if specified (used by _mv_ytdlp_stream_url)
            fmt_idx = next((i for i, a in enumerate(ytdlp_args) if a in ('-f', '--format')), None)
            if fmt_idx is not None and fmt_idx + 1 < len(ytdlp_args):
                ydl_opts['format'] = ytdlp_args[fmt_idx + 1]

            with _yt.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=False)
            out = _json.dumps(info)
            sys.__stderr__.write(f'[subrun ytdlp] ok url={url[:60]}\n')
            sys.__stderr__.flush()
            return _CP(0, out)
        except Exception as e:
            sys.__stderr__.write(f'[subrun ytdlp] error: {e}\n')
            sys.__stderr__.flush()
            return _CP(1, '', str(e))

    import sys as _sys
    _orig_run_dl = meowify_module._run_dl
    def _patched_run_dl(*a, **kw):
        _sys.__stderr__.write(f'[run_dl] called q={a[1] if len(a)>1 else "?"}\n')
        _sys.__stderr__.flush()
        return _orig_run_dl(*a, **kw)
    meowify_module._run_dl = _patched_run_dl
    meowify_module._subp    = _android_subp
    meowify_module._subrun  = _android_subrun


def _patch_media_session_js(meowify_module):
    """
    Inject JS into every HTML response that bridges playback state →
    AndroidBridge.onPlaybackState so the Android MediaSession (lock
    screen controls, headset buttons) stays in sync.
    Uses an after_request hook — no route re-registration needed.
    """
    _BRIDGE_JS = b"""<script>
(function(){
  if(typeof AndroidBridge==='undefined') return;
  var _lastKey = '';
  function _sync(){
    try{
      var playing = !!(window.S && S.playing);
      var song    = (S.queue && S.queue[S.queueIdx]) || {};
      var title   = song.title  || '';
      var artist  = song.artist || '';
      var key = playing + '|' + title + '|' + artist;
      if(key !== _lastKey){ _lastKey = key; AndroidBridge.onPlaybackState(playing, title, artist); }
    }catch(e){}
  }
  setInterval(_sync, 2000);
  // instant feedback on togglePlay
  document.addEventListener('DOMContentLoaded', function(){
    var _orig = window.togglePlay;
    if(typeof _orig === 'function'){
      window.togglePlay = function(){
        var r = _orig.apply(this, arguments);
        setTimeout(_sync, 150);
        return r;
      };
    }
  });
})();
</script></body>"""

    @meowify_module.app.after_request
    def _inject_media_bridge(response):
        if 'text/html' in response.content_type and response.status_code == 200:
            data = response.get_data()
            if b'</body>' in data:
                response.set_data(data.replace(b'</body>', _BRIDGE_JS, 1))
        return response

# ── 6. entry point called from ServerService.kt ───────────────────────────────

_server_started = False

def _prepare_ffmpeg_libs():
    import zipfile, stat, shutil
    ff = os.environ.get("MEOWIFY_FFMPEG", "")
    if not ff:
        return
    libdir = os.path.dirname(os.path.realpath(ff))
    zpath = os.path.join(libdir, "libffmpeg.zip.so")
    if not os.path.exists(zpath):
        sys.__stderr__.write('[ffmpeg] libffmpeg.zip.so missing in ' + libdir + '\n')
        sys.__stderr__.flush()
        return
    dest = os.path.join(os.path.dirname(os.path.dirname(ff)), "ffmpeg-pkg")
    stamp = os.path.join(dest, ".stamp")
    want = str(os.path.getsize(zpath))
    have = open(stamp).read() if os.path.exists(stamp) else ""
    if have != want:
        shutil.rmtree(dest, ignore_errors=True)
        os.makedirs(dest)
        with zipfile.ZipFile(zpath) as z:
            for info in z.infolist():
                target = os.path.join(dest, info.filename)
                if info.is_dir():
                    os.makedirs(target, exist_ok=True)
                    continue
                os.makedirs(os.path.dirname(target), exist_ok=True)
                if stat.S_ISLNK(info.external_attr >> 16):
                    os.symlink(z.read(info).decode(), target)
                else:
                    with z.open(info) as src, open(target, 'wb') as out:
                        shutil.copyfileobj(src, out)
        with open(stamp, 'w') as f:
            f.write(want)
    xz = os.path.join(libdir, "libffextra.zip.so")
    xstamp = os.path.join(dest, ".extra_stamp")
    if os.path.exists(xz):
        xwant = str(os.path.getsize(xz))
        if not os.path.exists(xstamp) or open(xstamp).read() != xwant:
            with zipfile.ZipFile(xz) as z:
                z.extractall(dest)
            with open(xstamp, 'w') as f:
                f.write(xwant)
    else:
        sys.__stderr__.write('[ffmpeg] libffextra.zip.so missing in ' + libdir + '\n')
        sys.__stderr__.flush()
    libs = os.path.join(dest, "usr", "lib")
    os.environ["LD_LIBRARY_PATH"] = libs + ":" + os.environ.get("LD_LIBRARY_PATH", "")
    sys.__stderr__.write('[ffmpeg] libs ready at ' + libs + '\n')
    sys.__stderr__.flush()

def _start_boot_confirm(meowify_module, port):
    """ota safety net: once the server has been up for a few seconds, mark the boot as good.
    kotlin rolls the ota back if .booting is still there on the next start."""
    import threading, time
    ota = os.environ.get("MEOWIFY_OTA_DIR", "")
    if not ota:
        return
    def _run():
        try:
            if not meowify_module.wait_for_server("127.0.0.1", port, timeout=60):
                return
            time.sleep(3)
            try:
                os.remove(os.path.join(ota, ".booting"))
            except FileNotFoundError:
                pass
        except Exception as e:
            sys.__stderr__.write(f'[ota] boot confirm failed: {e}\n')
            sys.__stderr__.flush()
    threading.Thread(target=_run, daemon=True).start()

def main_android(port: int, data_dir: str = "", ffmpeg_path: str = ""):
    import sys as _sys
    global _data_dir, _server_started
    if data_dir:
        _data_dir = data_dir
        os.makedirs(_data_dir, exist_ok=True)
    _sys.__stderr__.write(f'[android_patch] main_android called, data_dir={_data_dir}\n')
    _sys.__stderr__.flush()
    """
    Main entry point. Called from Kotlin via:
        py.getModule("android_patch").callAttr("main_android", port, dataDir)
    """
    # Chaquopy's Python interpreter (and its sys.modules cache) is
    # process-wide and outlives individual ServerService restarts within the
    # same OS process. If main_android() is somehow invoked a second time
    # (e.g. the old server thread wasn't fully torn down before a relaunch
    # re-triggered startServer() in Kotlin), `import meowify` below would
    # return the already-initialized, already-serving app object, and
    # re-registering after_request on it crashes with "app has already
    # handled its first request". Refuse outright rather than corrupt state
    # or spin up a second Flask instance alongside a possibly-still-alive one.
    if _server_started:
        _sys.__stderr__.write(
            '[android_patch] main_android called again in an already-initialized '
            'interpreter - refusing to re-run setup or start a second Flask instance. '
            'This means the previous server thread was not fully stopped before '
            'this restart; that is a Kotlin-side lifecycle bug, not fixable from here.\n'
        )
        _sys.__stderr__.flush()
        return
    _server_started = True

    # ffmpeg first — yt-dlp needs it in PATH before any download attempt
    if ffmpeg_path:
        os.environ["MEOWIFY_FFMPEG"] = ffmpeg_path
    _prepare_ffmpeg_libs()
    _patch_ffmpeg()

    # sys.path (ota deps -> ota code -> bundled code) is set up by ServerService.kt;
    # do not insert the data dir here, it would shadow the ota copies

    # import meowify — module-level code runs here (load_cfg etc)
    _ota_dir = _ensure_ota_env()
    import meowify
    # module may have been imported earlier with the env var missing
    if getattr(meowify, "_OTA_DIR", None) is None:
        from pathlib import Path as _P
        meowify._OTA_DIR = _P(_ota_dir)

    # apply all patches immediately after import
    _patch_paths(meowify)
    import sys as _sys2
    _sys2.__stderr__.write(f'[android_patch] _HOME={meowify._HOME} ddir={meowify.ddir()}\n')
    _sys2.__stderr__.flush()
    _patch_loudness(meowify)
    _patch_subp(meowify)
    _patch_media_session_js(meowify)
    # _patch_ytdlp_cmd intentionally removed — logic merged into _patch_subp

    # re-run mkdirs now that paths are correct
    meowify.mkdirs()

    # init database with correct path
    meowify.init_db()

    # write port file so MainActivity can find the server
    from pathlib import Path
    port_file = Path(meowify.ddir()) / ".port"
    port_file.parent.mkdir(parents=True, exist_ok=True)
    port_file.write_text(str(port))

    import sys as _sys2
    _sys2.__stderr__.write(f'[android_patch] port_file={port_file} ddir={meowify.ddir()}\n')
    _sys2.__stderr__.flush()

    print(f"android_patch: starting flask on 127.0.0.1:{port}")
    _start_boot_confirm(meowify, port)

    # run flask — this call blocks until the process is killed
    meowify.app.run(
        host="127.0.0.1",
        port=port,
        debug=False,
        use_reloader=False,
        threaded=True,
    )
