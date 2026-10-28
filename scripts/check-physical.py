#!/usr/bin/env python3
"""仅在显式指定的Xiaomi17与Sony A7C II组合执行实机选片和取景验收"""
import argparse
import datetime
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--allow-camera-parameter-check', action='store_true', required=True)
args = parser.parse_args()
adb = [str(root / '.tools/android-sdk/platform-tools/adb'), '-s', args.serial]
model = subprocess.check_output(adb + ['shell', 'getprop', 'ro.product.model'], text=True).strip()
if model != '25113PN0EC':
    raise SystemExit('此脚本只适用于已经确认的Xiaomi17验收设备')
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
backup = root / f'artifacts/phone-before-physical-{stamp}.tar'
log = root / f'build-logs/physical-ui-{stamp}.log'
subprocess.run(adb + ['shell', 'am', 'force-stop', 'app.framelink.camera'], check=True)
with backup.open('wb') as output:
    subprocess.run(adb + ['exec-out', 'run-as', 'app.framelink.camera', 'tar', '-cf', '-', 'databases', 'shared_prefs'], stdout=output, check=True)
commands = [
    ['install', '-r', str(root / 'android/app/build/outputs/apk/debug/app-debug.apk')],
    ['install', '-r', str(root / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')],
    ['shell', 'pm', 'grant', 'app.framelink.camera', 'android.permission.ACCESS_LOCAL_NETWORK'],
    ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'app.framelink.camera.PhysicalCoreUiAcceptanceTest',
     '-e', 'allowPhysicalCameraRead', 'true', 'app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner'],
]
result = 0
try:
    with log.open('w') as output:
        for command in commands:
            output.write('COMMAND: ' + ' '.join(command) + '\n')
            output.flush()
            try:
                result = subprocess.run(adb + command, stdout=output, stderr=subprocess.STDOUT, timeout=300).returncode
            except subprocess.TimeoutExpired:
                output.write('\n真机验收超时，需检查光圈恢复记录\n')
                result = 124
            if result:
                break
finally:
    subprocess.run(adb + ['shell', 'am', 'start', '-n', 'app.framelink.camera/.MainActivity'], capture_output=True)
text = log.read_text()
if 'FAILURES!!!' in text or 'INSTRUMENTATION_FAILED' in text or 'OK (2 tests)' not in text:
    result = result or 1
log.with_suffix('.exit').write_text(str(result))
print(f'physical: exit={result}; log={log}; backup={backup}')
raise SystemExit(result)
