#!/usr/bin/env python3
"""冻结连接与历史功能测试包，只使用本轮检查和安装前收据，不改写旧版冻结包"""
import hashlib
import json
import pathlib
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
artifacts = root / 'artifacts'
version = '0.1.25'
checks = ['android-0.1.25-candidate', 'ios-0.1.7-foundations-core', 'ios-0.1.7-foundations-app', 'ios-0.1.7-ui-build', 'ios-0.1.7-device']
for name in checks:
    assert (root / 'build-logs' / (name + '.exit')).read_text().strip() == '0', name
subprocess.run(['git', 'diff', '--exit-code', 'HEAD'], cwd=root, check=True)
assert not subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root).strip(), '请先提交本轮源码'
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
main = root / 'android/app/build/outputs/apk/debug/app-debug.apk'
diagnostic = root / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
runs = sorted((artifacts / f'FrameLink-{version}-evidence').glob('*/receipt.json'))
matches = [path for path in runs if (lambda r: r['exit'] == 0 and r['installedBeforeRun'] and r['buildOutputsUnchanged'] and r['apkSha256'] == digest(main) and r['diagnosticSha256'] == digest(diagnostic))(json.loads(path.read_text()))]
assert matches, '缺少当前主包和诊断组件的通过收据'
receipt = matches[-1]
assert 'OK (6 tests)' in (receipt.parent / 'test-run.log').read_text(), '本轮应执行6项新增验收'
results = list((root / 'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
suites = [ET.parse(path).getroot() for path in results]
assert sum(int(suite.get('tests', '0')) for suite in suites) == 62
assert all(int(suite.get('failures', '0')) + int(suite.get('errors', '0')) == 0 for suite in suites)
tools = root / '.tools/android-sdk/build-tools/36.0.0'
certificates = [subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(path)], text=True) for path in [main, diagnostic]]
signers = [next(line for line in text.splitlines() if 'certificate SHA-256 digest:' in line) for text in certificates]
assert signers[0] == signers[1], '主包与诊断签名不同'
subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(main)], check=True, capture_output=True)
evidence = receipt.parent / 'software-checks'
evidence.mkdir(exist_ok=True)
for name in checks:
    for suffix in ['.log', '.exit']:
        shutil.copy2(root / 'build-logs' / (name + suffix), evidence / (name + suffix))
for path in results:
    shutil.copy2(path, evidence / path.name)
outputs = {}
for name, source in [('debug.apk', main), ('diagnostics.apk', diagnostic)]:
    target = artifacts / f'FrameLink-{version}-{name}'
    if target.exists():
        assert digest(target) == digest(source), '已有冻结文件不同：' + target.name
    else:
        shutil.copy2(source, target)
    outputs[name] = {'file': target.name, 'sha256': digest(target), 'bytes': target.stat().st_size}
source = artifacts / f'FrameLink-{version}-source.zip'
assert not source.exists(), '源码冻结文件已存在，请勿覆盖'
subprocess.run(['git', 'archive', '--format=zip', '--prefix=camera-assistant/', '-o', str(source), commit], cwd=root, check=True)
outputs['source'] = {'file': source.name, 'sha256': digest(source), 'bytes': source.stat().st_size}
manifest = {'version': version, 'qualification': 'development-preview', 'sourceCommit': commit, 'outputs': outputs,
            'androidJvmTests': 62, 'androidInstalledTests': 6, 'iosCoreTests': 14,
            'receipt': str(receipt.relative_to(artifacts)),
            'evidenceFiles': [{'file': str(path.relative_to(artifacts)), 'sha256': digest(path)} for path in sorted(receipt.parent.rglob('*')) if path.is_file()],
            'iosUITestsExecuted': False, 'iosUIBlocker': 'CoreSimulator 1174.9.2 is older than Xcode-required 1174.21.0',
            'physicalDeviceAccepted': False, 'publicRelease': False}
ios = json.loads((artifacts / 'FrameLink-0.1.7-ios-unsigned.json').read_text())
assert digest(artifacts / ios['ipa']) == ios['sha256'], 'iPhone产物摘要不符'
manifest['iosArtifact'] = ios
target = artifacts / f'FrameLink-{version}-manifest.json'
target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print(target)
for value in outputs.values():
    print(value['file'] + ' ' + value['sha256'])
