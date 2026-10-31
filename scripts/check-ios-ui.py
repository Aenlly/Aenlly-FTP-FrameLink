#!/usr/bin/env python3
"""在明确选择的iOS模拟器验证本地图库、FTP入口和本机模拟相机，不操作实体iPhone"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import plistlib
import platform
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--device', help='专用测试模拟器的UDID')
parser.add_argument('--suite', choices=['full', 'boundaries', 'index', 'gallery-window'], default='full', help='boundaries检查4项既有边界；index检查合成分页；gallery-window仅检查图库与大图受影响UI')
args = parser.parse_args()
inventory = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', 'available', '--json'], text=True))
devices = [dict(device, runtime=runtime) for runtime, group in inventory['devices'].items() for device in group]
device = next((item for item in devices if item['udid'] == args.device), None) if args.device else next((item for item in devices if item['name'].startswith('FrameLink QA')), None)
if device is None:
    raise SystemExit('请先创建专用iPhone模拟器，并用--device指定UDID；默认仅查找名称以FrameLink QA开头的设备')
if args.suite != 'full' and (not args.device or not device['name'].startswith('FrameLink QA')):
    raise SystemExit('专项验收必须显式指定本项目FrameLink QA模拟器，不操作其他设备')

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()

def bundle_identity(path):
    info = plistlib.loads((path / 'Info.plist').read_bytes())
    value = hashlib.sha256()
    for item in sorted(path.rglob('*')):
        if item.is_file():
            value.update(str(item.relative_to(path)).encode() + b'\0')
            value.update(bytes.fromhex(digest(item)))
    return {'path': str(path), 'bundleIdentifier': info.get('CFBundleIdentifier'),
            'version': info.get('CFBundleShortVersionString'), 'build': info.get('CFBundleVersion'),
            'executableSha256': digest(path / info['CFBundleExecutable']), 'treeSha256': value.hexdigest()}

def boundaries():
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    directory = root / 'artifacts' / ('ios-ui-' + args.suite + '-' + stamp)
    directory.mkdir(parents=True)
    tests = ['testCameraUnsavedEndpointAndAccountEditsClearCredentialsButSelectingBPreservesThem',
             'testFTPUnsavedEndpointAndAccountEditsClearCredentialsButSelectingBPreservesThem',
             'testPhotoMetadataUsesFileSettingsAndCacheClearKeepsImportedPhoto',
             'testOpenPhotoInformationRefreshesWhenSamePhotoGetsLocalOriginal']
    if args.suite == 'index':
        tests = ['testCameraIndexShowsRangeTotalAndFillsWithoutScrollingBeforeSelectAll']
    elif args.suite == 'gallery-window':
        tests = ['testPhotoWorkspaceKeepsPhotoZoomAndSelectionAcrossRotationAndComparison',
                 'testOpenPhotoInformationRefreshesWhenSamePhotoGetsLocalOriginal',
                 'testGalleryLayoutsPreservePairedFormatInformation']
    def capture(command):
        return subprocess.check_output(command, cwd=root, text=True).strip()
    receipt = {'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'sourceCommit': capture(['git', 'rev-parse', 'HEAD']),
               'sourceWorktreeDirty': bool(capture(['git', 'status', '--porcelain'])),
               'device': device, 'hostArchitecture': platform.machine(),
               'xcode': capture(['xcodebuild', '-version']),
               'developerDirectory': os.environ.get('DEVELOPER_DIR') or capture(['xcode-select', '-p']),
               'developerDirectoryOverride': os.environ.get('DEVELOPER_DIR'),
               'simctl': capture(['xcrun', '--find', 'simctl']), 'tests': tests,
               'physicalIPhoneVerified': False, 'buildExit': None, 'testExit': None}
    runtimes = json.loads(capture(['xcrun', 'simctl', 'list', 'runtimes', '--json']))
    receipt['runtime'] = next((item for item in runtimes['runtimes'] if item['identifier'] == device['runtime']), None)
    products = root / 'ios/DerivedData/Build/Products/Debug-iphonesimulator'
    result = directory / 'test-results.xcresult'
    def save():
        receipt['logs'] = [{'file': item.name, 'sha256': digest(item)} for item in sorted(directory.glob('*.log'))]
        (directory / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
    def execute(command, name, timeout):
        with (directory / (name + '.log')).open('w') as log:
            log.write('COMMAND=' + json.dumps(command, ensure_ascii=False) + '\n'); log.flush()
            try:
                return subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=timeout).returncode
            except subprocess.TimeoutExpired:
                log.write('\n专项验收超时；未停止其他设备或系统服务\n'); return 124
    print('专项验收归档：' + str(directory), flush=True)
    save()
    build = ['xcodebuild', '-project', 'ios/FrameLink.xcodeproj', '-scheme', 'FrameLink', '-configuration', 'Debug',
             '-sdk', 'iphonesimulator', '-destination', 'generic/platform=iOS Simulator', '-derivedDataPath', 'ios/DerivedData',
             'CODE_SIGNING_ALLOWED=YES', 'CODE_SIGN_IDENTITY=-', 'CODE_SIGN_STYLE=Manual', 'build-for-testing']
    receipt['buildExit'] = execute(build, 'build', 600); save()
    if receipt['buildExit']:
        return receipt['buildExit']
    app = products / 'FrameLink.app'
    runner = products / 'FrameLinkUITests-Runner.app'
    receipt['appBeforeRun'] = bundle_identity(app); receipt['testRunnerBeforeRun'] = bundle_identity(runner)
    save()
    command = ['xcodebuild', '-project', 'ios/FrameLink.xcodeproj', '-scheme', 'FrameLink', '-configuration', 'Debug',
               '-destination', 'platform=iOS Simulator,id=' + device['udid'], '-derivedDataPath', 'ios/DerivedData',
               '-parallel-testing-enabled', 'NO', '-collect-test-diagnostics', 'never', '-test-timeouts-enabled', 'YES',
               '-default-test-execution-time-allowance', '120', '-resultBundlePath', str(result), 'test-without-building']
    command += ['-only-testing:FrameLinkUITests/FrameLinkUITests/' + name for name in tests]
    receipt['testExit'] = execute(command, 'tests', 1200)
    receipt['buildOutputsUnchanged'] = bundle_identity(app) == receipt['appBeforeRun'] and bundle_identity(runner) == receipt['testRunnerBeforeRun']
    for label, built in [('installedApp', receipt['appBeforeRun']), ('installedTestRunner', receipt['testRunnerBeforeRun'])]:
        try:
            location = capture(['xcrun', 'simctl', 'get_app_container', device['udid'], built['bundleIdentifier'], 'app'])
            receipt[label] = bundle_identity(pathlib.Path(location))
            receipt[label + 'ExecutableMatches'] = receipt[label]['executableSha256'] == built['executableSha256']
        except (subprocess.CalledProcessError, OSError) as error:
            receipt[label + 'Error'] = str(error)
    if result.exists():
        summary = subprocess.run(['xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', str(result)], capture_output=True, text=True)
        (directory / 'result-summary.log').write_text(summary.stdout + summary.stderr)
        if summary.returncode == 0:
            receipt['summary'] = json.loads(summary.stdout)
    screenshot = directory / 'device-after-tests.png'
    shot = subprocess.run(['xcrun', 'simctl', 'io', device['udid'], 'screenshot', str(screenshot)], capture_output=True, text=True)
    if shot.returncode == 0:
        import struct
        receipt['screenPixels'] = list(struct.unpack('>II', screenshot.read_bytes()[16:24]))
        receipt['screenshotSha256'] = digest(screenshot)
    receipt['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat(); save()
    summary = receipt.get('summary', {})
    receipt['qualified'] = (receipt['testExit'] == 0 and receipt['buildOutputsUnchanged']
                            and receipt.get('installedAppExecutableMatches', False)
                            and receipt.get('installedTestRunnerExecutableMatches', False)
                            and summary.get('totalTestCount') == len(tests)
                            and summary.get('passedTests') == len(tests)
                            and summary.get('failedTests') == 0 and summary.get('skippedTests') == 0)
    save()
    return receipt['testExit'] or (0 if receipt['qualified'] else 1)

if args.suite != 'full':
    raise SystemExit(boundaries())
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
command = ['xcodebuild', '-project', 'ios/FrameLink.xcodeproj', '-scheme', 'FrameLink', '-configuration', 'Debug',
           '-destination', 'platform=iOS Simulator,id=' + device['udid'], '-derivedDataPath', 'ios/DerivedData',
           '-parallel-testing-enabled', 'NO', '-collect-test-diagnostics', 'never', '-test-timeouts-enabled', 'YES',
           '-default-test-execution-time-allowance', '120', '-resultBundlePath', f'artifacts/ios-ui-{stamp}.xcresult',
           'CODE_SIGNING_ALLOWED=YES', 'CODE_SIGN_IDENTITY=-', 'CODE_SIGN_STYLE=Manual', 'test']
print(f"仅验证模拟器：{device['name']} ({device['udid']})", flush=True)
raise SystemExit(subprocess.run(command, cwd=root).returncode)
