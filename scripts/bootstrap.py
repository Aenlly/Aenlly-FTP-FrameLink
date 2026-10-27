#!/usr/bin/env python3
"""在项目内准备编译工具，不修改用户的全局环境"""
import hashlib
import pathlib
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
DOWNLOADS = ROOT / 'downloads'
TOOLS.mkdir(exist_ok=True)
DOWNLOADS.mkdir(exist_ok=True)


def download(url, name, sha256=None):
    path = DOWNLOADS / name
    if not path.exists():
        urllib.request.urlretrieve(url, path)
    if sha256 and hashlib.sha256(path.read_bytes()).hexdigest() != sha256:
        raise RuntimeError(f'校验失败，请重新下载 {path}')
    return path


if not (TOOLS / 'go/bin/go').exists():
    archive = download('https://go.dev/dl/go1.26.10.darwin-arm64.tar.gz', 'go.tar.gz',
                       '0b5183492292ada170f9b561a834863808dd02edba599e4ad0e89899330020b3')
    with tarfile.open(archive) as src:
        src.extractall(TOOLS, filter='data')

sdk = TOOLS / 'android-sdk'
manager = sdk / 'cmdline-tools/latest/bin/sdkmanager'
if not manager.exists():
    archive = download('https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip', 'android-tools.zip')
    staging = TOOLS / 'sdk-staging'
    with zipfile.ZipFile(archive) as src:
        src.extractall(staging)
    (sdk / 'cmdline-tools').mkdir(parents=True, exist_ok=True)
    shutil.move(str(staging / 'cmdline-tools'), str(sdk / 'cmdline-tools/latest'))
    for p in (sdk / 'cmdline-tools/latest/bin').iterdir():
        p.chmod(0o755)

print(f'GO: {TOOLS}/go/bin/go')
print(f'ANDROID_HOME: {sdk}')
print('SDK组件由scripts/build-android.sh检查和安装')
