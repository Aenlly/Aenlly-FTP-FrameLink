#!/usr/bin/env python3
"""在明确指定的小米17或红米设备验证Sony A7C II，手动安装与应用内触控需显式选择"""
import argparse
import datetime
import hashlib
import pathlib
import re
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--allow-camera-parameter-check', action='store_true', required=True)
parser.add_argument('--manual-install', action='store_true', help='使用已在手机手动安装的App和诊断组件')
parser.add_argument('--version', help='手动安装模式核对指定的已冻结版本，不使用开发工作区版本')
parser.add_argument('--app-touch', action='store_true', help='仅向App自己的窗口分发触控，不验证系统输入注入')
parser.add_argument('--case', choices=['all', 'gallery', 'live', 'focus', 'recover', 'latency', 'creative', 'creative-ui'], default='all')
parser.add_argument('--focus-near', help='仅对焦验收：前景目标归一化坐标x,y')
parser.add_argument('--focus-far', help='仅对焦验收：后景目标归一化坐标x,y')
parser.add_argument('--include-ftp', action='store_true', help='追加本机FTP客户端字节、中文文件名与取消校验')
parser.add_argument('--include-capture', action='store_true', help='明确执行一次真实快门，保留机身照片并验证数量、状态和进入图库连接')
parser.add_argument('--include-parameter-write', action='store_true', help='明确进行光圈写入与恢复；默认只读检查取景UI')
args = parser.parse_args()
if args.version and (not args.manual_install or not re.fullmatch(r'\d+\.\d+\.\d+', args.version)):
    parser.error('--version仅用于指定手动安装的冻结版本')
for point in [args.focus_near, args.focus_far]:
    if point:
        coordinates = [float(value) for value in point.split(',')]
        if len(coordinates) != 2 or not all(0 <= value <= 1 for value in coordinates):
            parser.error('对焦坐标需为0到1之间的x,y')
adb = [str(root / '.tools/android-sdk/platform-tools/adb'), '-s', args.serial]
version = args.version or re.search(r'versionName\s*=\s*"([^"]+)"', (root / 'android/app/build.gradle.kts').read_text()).group(1)
if args.version and not (root / f'artifacts/FrameLink-{version}-manifest.json').is_file():
    raise SystemExit('缺少指定版本的冻结清单，停止测试')
expected_packages = {
    'app.framelink.camera': root / (f'artifacts/FrameLink-{version}-debug.apk' if args.manual_install else 'android/app/build/outputs/apk/debug/app-debug.apk'),
    'app.framelink.camera.test': root / (f'artifacts/FrameLink-{version}-diagnostics.apk' if args.manual_install else 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'),
}

def verify_installed_packages():
    for package, expected in expected_packages.items():
        if not expected.is_file():
            raise SystemExit(f'缺少本轮安装包：{expected}')
        paths = subprocess.check_output(adb + ['shell', 'pm', 'path', package], text=True).splitlines()
        if len(paths) != 1 or not paths[0].startswith('package:/data/app/'):
            raise SystemExit(f'未找到可核对的整包：{package}')
        actual = subprocess.check_output(adb + ['shell', 'sha256sum', paths[0].removeprefix('package:')], text=True).split()[0]
        digest = hashlib.sha256()
        with expected.open('rb') as source:
            for block in iter(lambda: source.read(1024 * 1024), b''):
                digest.update(block)
        if actual != digest.hexdigest():
            raise SystemExit(f'{package}不是本轮构建，停止测试；请先覆盖安装主包和诊断组件')

model = subprocess.check_output(adb + ['shell', 'getprop', 'ro.product.model'], text=True).strip()
if model not in {'25113PN0EC', '23013RK75C'}:
    raise SystemExit('设备不在本项目已确认的实机验收范围内')
sdk = int(subprocess.check_output(adb + ['shell', 'getprop', 'ro.build.version.sdk'], text=True).strip())
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
if args.manual_install:
    verify_installed_packages()
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
base_class = 'app.framelink.camera.PhysicalCoreUiAcceptanceTest'
methods = {'gallery': 'connectedGallerySelectionDownloadBatchAndReconnectWorkOnPhone',
    'live': 'liveLabelsAndFooterStayVisibleAndApertureRestoresExactly', 'focus': 'focusTargetsDoNotFireShutter',
    'recover': 'recoverPendingFilesWithoutTakingPhoto', 'latency': 'measurePreviewReceptionAndNetworkWithoutCapture',
    'creative': 'readCreativeLookDescriptorsWithoutChangingCameraSettings','creative-ui':'creativeLookUiReadsActualCameraSelectionWithoutWriting'}
test_class = ','.join(base_class + '#' + methods[case] for case in (['gallery', 'live'] if args.case == 'all' else [args.case]))
if args.include_capture:
    test_class += ',' + base_class + '#singleShotFinishesWithoutImportAndAlbumKeepsConnection'
if args.include_ftp:
    test_class += ',app.framelink.camera.FtpClientAcceptanceTest#protocolRoundTripChineseNamesAuthenticationAndNoOverwrite'
instrument = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test_class,
    '-e', 'allowPhysicalCameraRead', 'true', '-e', 'appTouch', str(args.app_touch).lower(),
    '-e', 'allowPhysicalCapture', str(args.include_capture).lower(),
    '-e', 'allowPhysicalParameterWrite', str(args.include_parameter_write).lower(),
    'app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner']
for key, point in [('focusNear', args.focus_near), ('focusFar', args.focus_far)]:
    if point: instrument[-1:-1] = ['-e', key, point]
commands.append(instrument)
result = 0
try:
    with log.open('w') as output:
        for command in commands:
            if command[:3] == ['shell', 'am', 'instrument']:
                verify_installed_packages()
            output.write('COMMAND: ' + ' '.join(command) + '\n')
            output.flush()
            try:
                result = subprocess.run(adb + command, stdout=output, stderr=subprocess.STDOUT, timeout=300).returncode
            except subprocess.TimeoutExpired:
                output.write('\n真机验收超时，需检查光圈恢复记录\n')
                subprocess.run(adb + ['shell', 'am', 'force-stop', 'app.framelink.camera'], stdout=output, stderr=subprocess.STDOUT)
                result = 124
            if result:
                break
finally:
    subprocess.run(adb + ['shell', 'am', 'start', '-f', '0x20000000', '-n', 'app.framelink.camera/.MainActivity'], capture_output=True)
text = log.read_text()
test_count = (2 if args.case == 'all' else 1) + int(args.include_ftp) + int(args.include_capture)
expected = f'OK ({test_count} {"test" if test_count == 1 else "tests"})'
if 'FAILURES!!!' in text or 'INSTRUMENTATION_FAILED' in text or expected not in text:
    result = result or 1
log.with_suffix('.exit').write_text(str(result))
print(f'physical: exit={result}; log={log}; backup={backup}')
raise SystemExit(result)
