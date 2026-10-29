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
configurations=[]
receipts=[]
for theme in ['bright','dark']:
    for variant in ['regular','large']:
        directory=artifacts/f'camera-ui-{version}-{theme}{"-large" if variant=="large" else ""}'
        receipt=json.loads((directory/'receipt.json').read_text())
        receipt_path=directory/'receipt.json'
        receipts.append({'file':str(receipt_path.relative_to(artifacts)),'sha256':hashlib.sha256(receipt_path.read_bytes()).hexdigest()})
        if receipt['packages']['app.framelink.camera']!=flow['apkSha256']or receipt['packages']['app.framelink.camera.test']!=flow['diagnosticSha256']:raise SystemExit('取景截图与连续流程不是同一构建')
        required={'portrait-menu','landscape-menu','portrait-assist-menu','landscape-assist-menu'}
        if not required.issubset(receipt['images']):raise SystemExit('两个方向的机身参数与预览辅助证据不齐')
        if receipt['variant']!=variant or (variant=='large' and receipt['fontScale']<1.3):raise SystemExit('工具菜单字号配置不一致')
        configurations.append({'theme':theme,'variant':variant,'fontScale':receipt['fontScale']})
        for item in receipt['images'].values():
            path=directory/item['file']
            if hashlib.sha256(path.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('截图散列不匹配')
            images.append({'file':str(path.relative_to(artifacts)),'sha256':item['sha256'],'theme':theme,'variant':variant,'dimensions':item['dimensions']})
video=artifacts/flow['video']['file']
if hashlib.sha256(video.read_bytes()).hexdigest()!=flow['video']['sha256']:raise SystemExit('录屏散列不匹配')
result={**flow,'screenshots':images,'menuConfigurations':configurations,'receipts':receipts,'preview':'camera protocol simulator','physicalCameraVerified':False}
path=artifacts/f'camera-ui-{version}-evidence.json'
path.write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(str(path));print('screenshots='+str(len(images)))
