import os
import sys
import shutil
import runpy
from pathlib import Path

D = Path(__file__).resolve().parent
os.chdir(D)

log = D / 'meowify.log'
try:
    if log.exists() and log.stat().st_size > 2_000_000:
        log.replace(D / 'meowify.log.1')
except Exception:
    pass
if sys.stdout is None or sys.stderr is None:
    _f = open(log, 'a', encoding='utf-8', buffering=1)
    if sys.stdout is None:
        sys.stdout = _f
    if sys.stderr is None:
        sys.stderr = _f

if sys.platform == 'win32':
    MU = Path(os.environ.get('LOCALAPPDATA', Path.home() / 'AppData' / 'Local')) / 'Programs' / 'motionui'
else:
    MU = Path.home() / '.local' / 'lib' / 'motionui'

flag = D / '.booting'
bak = D / 'backup'
if flag.exists() and bak.exists():
    try:
        for name in ('meowify.py', 'requirements.txt'):
            if (bak / name).exists():
                shutil.copy2(bak / name, D / name)
        if (bak / 'install.json').exists():
            shutil.copy2(bak / 'install.json', D / '.install.json')
        if (bak / 'motionui').exists():
            shutil.rmtree(MU, ignore_errors=True)
            shutil.copytree(bak / 'motionui', MU)
        print('[launcher] last update did not start cleanly, rolled back')
    except Exception as e:
        print(f'[launcher] rollback failed: {e}')
    finally:
        try:
            flag.unlink()
        except Exception:
            pass

sys.path.insert(0, str(D))
runpy.run_path(str(D / 'meowify.py'), run_name='__main__')