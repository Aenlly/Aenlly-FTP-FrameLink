#!/usr/bin/env python3
"""下载许可明确的真实摄影样片，仅放入本地验收目录，不随安装包分发"""
import concurrent.futures
import hashlib
import json
import pathlib
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
items = json.loads((root / 'scripts/visual-fixtures.json').read_text())
target = root / 'artifacts/visual-fixtures'
target.mkdir(parents=True, exist_ok=True)

def fetch(item):
    path = target / item['file']
    if not path.exists():
        request = urllib.request.Request(item['image'], headers={'User-Agent': 'FrameLink-Visual-QA/0.1.2'})
        with urllib.request.urlopen(request, timeout=45) as response:
            data = response.read(12 * 1024 * 1024 + 1)
        if not data.startswith(b'\xff\xd8') or len(data) > 12 * 1024 * 1024:
            raise ValueError('不是大小受控的JPEG样片：' + item['file'])
        temporary = path.with_suffix('.part')
        temporary.write_bytes(data)
        temporary.replace(path)
    result = dict(item, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), bytes=path.stat().st_size)
    if result['sha256'] != item['sha256']:
        raise ValueError('样片散列与已核验版本不一致：' + item['file'])
    print(result['file'], result['bytes'], result['sha256'])
    return result

with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    results = list(pool.map(fetch, items))
(target / 'attribution.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
print('样片仅用于界面验收，作者和许可记录于 ' + str(target / 'attribution.json'))
