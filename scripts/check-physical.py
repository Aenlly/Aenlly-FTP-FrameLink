#!/usr/bin/env python3
"""在明确指定的小米17或红米设备验证Sony A7C II，手动安装与应用内触控需显式选择"""
import argparse
import datetime
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--allow-camera-parameter-check', action='store_true', required=True)
parser.add_argument('--manual-install', action='store_true', help='使用已在手机手动安装的App和诊断组件')
parser.add_argument('--app-touch', action='store_true', help='仅向App自己的窗口分发触控，不验证系统输入注入')
parser.add_argument('--case', choices=['all', 'gallery', 'live'], default='all')
parser.add_argument('--include-ftp', action='store_true', help='追加本机FTP客户端字节、中文文件名与取消校验')
args = parser.parse_args()
adb = [str(root / '.tools/android-sdk/platform-tools/adb'), '-s', args.serial]
model = subprocess.check_output(adb + ['shell', 'getprop', 'ro.product.model'], text=True).strip()
if model not in {'25113PN0EC', '23013RK75C'}:
    raise SystemExit('设备不在本项目已确认的实机验收范围内')
sdk = int(subprocess.check_output(adb + ['shell', 'getprop', 'ro.build.version.sdk'], text=True).strip())
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
backup = root / f'artifacts/phone-before-physical-{stamp}.tar'
log = root / f'build-logs/physical-ui-{stamp}.log'
subprocess.run(adb + ['shell', 'am', 'force-stop', 'app.framelink.camera'], check=True)
with backup.open('wb') as output:
    subprocess.run(adb + ['exec-out', 'run-as', 'app.framelink.camera', 'tar', '-cf', '-', 'databases', 'shared_prefs'], stdout=output, check=True)
commands = [] if args.manual_install else [
    ['install', '-r', str(root / 'android/app/build/outputs/apk/debug/app-debug.apk')],
    ['install', '-r', str(root / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')],
]
if sdk >= 37:
    commands.append(['shell', 'pm', 'grant', 'app.framelink.camera', 'android.permission.ACCESS_LOCAL_NETWORK'])
test_class = 'app.framelink.camera.PhysicalCoreUiAcceptanceTest'
if args.case != 'all':
    test_class += '#' + {'gallery': 'connectedGallerySelectionDownloadBatchAndReconnectWorkOnPhone',
        'live': 'liveLabelsAndFooterStayVisibleAndApertureRestoresExactly'}[args.case]
if args.include_ftp:
    test_class += ',app.framelink.camera.FtpClientAcceptanceTest#protocolRoundTripChineseNamesAuthenticationAndNoOverwrite'
commands.append(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test_class,
    '-e', 'allowPhysicalCameraRead', 'true', '-e', 'appTouch', str(args.app_touch).lower(),
    'app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner'])
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
test_count = (2 if args.case == 'all' else 1) + int(args.include_ftp)
expected = f'OK ({test_count} {"test" if test_count == 1 else "tests"})'
if 'FAILURES!!!' in text or 'INSTRUMENTATION_FAILED' in text or expected not in text:
    result = result or 1
log.with_suffix('.exit').write_text(str(result))
print(f'physical: exit={result}; log={log}; backup={backup}')
raise SystemExit(result)
