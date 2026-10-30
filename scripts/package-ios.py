#!/usr/bin/env python3
"""打包供自签工具处理的iPhone IPA，不把模拟器或无签名包标为可直接安装"""
import hashlib
import json
import pathlib
import plistlib
import re
import shutil
import subprocess
import tempfile
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
source = root / 'ios/DerivedData-device/Build/Products/Debug-iphoneos/FrameLink.app'
if not source.is_dir():
    raise SystemExit('先执行python3 scripts/check.py ios-device构建iPhone App')
info = plistlib.loads((source / 'Info.plist').read_bytes())
if info.get('CFBundleSupportedPlatforms') != ['iPhoneOS']:
    raise SystemExit('只接受iPhoneOS产物，不能使用模拟器App')
executable = source / info['CFBundleExecutable']
architectures = subprocess.check_output(['lipo', '-archs', str(executable)], text=True).split()
platform = subprocess.check_output(['xcrun', 'vtool', '-show-build', str(executable)], text=True)
if architectures != ['arm64'] or not re.search(r'platform\s+IOS\s*$', platform, re.MULTILINE):
    raise SystemExit('主程序必须是iOS设备arm64架构')
artifacts = root / 'artifacts'
artifacts.mkdir(exist_ok=True)
ipa = artifacts / f'FrameLink-{info["CFBundleShortVersionString"]}-ios-unsigned.ipa'
if ipa.exists():
    raise SystemExit('该版本IPA已存在，保留历史文件；请递增版本后生成新候选包')
with tempfile.TemporaryDirectory(prefix='framelink-ipa-') as temporary:
    payload = pathlib.Path(temporary) / 'Payload'
    app = payload / source.name
    shutil.copytree(source, app, ignore=shutil.ignore_patterns('_CodeSignature', 'embedded.mobileprovision', '.DS_Store'))
    signature = subprocess.run(['codesign', '-d', str(app)], capture_output=True)
    if signature.returncode == 0:
        subprocess.run(['codesign', '--remove-signature', str(app)], check=True, capture_output=True)
    if subprocess.run(['codesign', '-d', str(app)], capture_output=True).returncode == 0:
        raise SystemExit('未能清除构建时的临时签名')
    with zipfile.ZipFile(ipa, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(payload.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(temporary))
manifest = {
    'version': info['CFBundleShortVersionString'], 'build': info['CFBundleVersion'],
    'bundleIdentifier': info['CFBundleIdentifier'], 'minimumOS': info['MinimumOSVersion'],
    'platform': 'iPhoneOS', 'architectures': architectures, 'signing': 'unsigned-requires-resigning',
    'ipa': ipa.name, 'bytes': ipa.stat().st_size,
    'sha256': hashlib.sha256(ipa.read_bytes()).hexdigest(),
    'physicalIPhoneVerified': False,
    'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
    'sourceWorktreeDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root).strip()),
}
ipa.with_suffix('.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print(ipa)
print('需要通过自签工具或开发者证书签名后安装，尚未完成实体iPhone验收')
print('SHA256 ' + manifest['sha256'])
