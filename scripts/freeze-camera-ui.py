#!/usr/bin/env python3
"""核对同包明暗取景截图与连续操作证据，不替换散列或混用其他构建"""
import hashlib
import json
import pathlib
import re

root=pathlib.Path(__file__).resolve().parents[1]
artifacts=root/'artifacts'
version=re.search(r'versionName\s*=\s*"([^"]+)"',(root/'android/app/build.gradle.kts').read_text()).group(1)
flow=json.loads((artifacts/f'live-ui-{version}-flow-evidence.json').read_text())
if not flow['passed']:raise SystemExit('连续取景流程未通过')
images=[]
for theme in ['bright','dark']:
    directory=artifacts/f'camera-ui-{version}-{theme}'
    receipt=json.loads((directory/'receipt.json').read_text())
    if receipt['packages']['app.framelink.camera']!=flow['apkSha256']or receipt['packages']['app.framelink.camera.test']!=flow['diagnosticSha256']:raise SystemExit('取景截图与连续流程不是同一构建')
    for item in receipt['images'].values():
        path=directory/item['file']
        if hashlib.sha256(path.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('截图散列不匹配')
        images.append({'file':str(path.relative_to(artifacts)),'sha256':item['sha256'],'theme':theme,'dimensions':item['dimensions']})
video=artifacts/flow['video']['file']
if hashlib.sha256(video.read_bytes()).hexdigest()!=flow['video']['sha256']:raise SystemExit('录屏散列不匹配')
result={**flow,'screenshots':images,'preview':'camera protocol simulator','physicalCameraVerified':False}
path=artifacts/f'camera-ui-{version}-evidence.json'
path.write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(str(path));print('screenshots='+str(len(images)))
