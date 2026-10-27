#!/usr/bin/env python3
"""使用Google官方仓库清单校验SDK归档，处理sdkmanager归档读取失败"""
import hashlib
import pathlib
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import time
import sys
import stat

root=pathlib.Path(__file__).resolve().parents[1]
sdk=root/'.tools/android-sdk'
base='https://dl.google.com/android/repository/'
for attempt in range(6):
    try:
        manifest=ET.fromstring(urllib.request.urlopen(base+'repository2-3.xml',timeout=60).read())
        break
    except Exception:
        if attempt==5: raise
        time.sleep(2)
packages=sys.argv[1:] or ['platform-tools','platforms;android-36','build-tools;36.0.0','ndk;27.2.12479018']
for name in packages:
    package_base=base
    package_manifest=manifest
    if name.startswith('system-images;'):
        variant=name.split(';')[2]
        package_base=base+'sys-img/'+('android' if variant=='default' else variant)+'/'
        for attempt in range(6):
            try:
                package_manifest=ET.fromstring(urllib.request.urlopen(package_base+'sys-img2-3.xml',timeout=60).read())
                break
            except Exception:
                if attempt==5: raise
                time.sleep(2)
    destination=sdk.joinpath(*name.split(';'))
    marker='android.jar' if name.startswith('platforms;') else 'source.properties'
    if (destination/marker).exists():
        print('READY',name,flush=True)
        continue
    package=next(p for p in package_manifest.findall('{*}remotePackage') if p.attrib.get('path')==name)
    archives=package.findall('{*}archives/{*}archive')
    candidates=[a for a in archives if a.findtext('{*}host-os') in (None,'macosx')]
    archive=next((a for a in candidates if a.findtext('{*}host-arch') in ('aarch64','arm64')),candidates[0])
    complete=archive.find('{*}complete')
    filename=complete.findtext('{*}url')
    checksum=complete.find('{*}checksum')
    expected=checksum.text.strip()
    algorithm=checksum.attrib.get('type','sha1')
    target=root/'downloads'/filename
    expected_size=int(complete.findtext('{*}size'))
    failures=0
    while failures<8:
        offset=target.stat().st_size if target.exists() else 0
        if offset==expected_size: break
        if offset>expected_size:
            target.unlink()
            offset=0
        print('DOWNLOAD',name,offset,'/',expected_size,flush=True)
        try:
            end=min(offset+8*1024*1024-1,expected_size-1)
            request=urllib.request.Request(package_base+filename,headers={'Range':f'bytes={offset}-{end}'})
            with urllib.request.urlopen(request,timeout=90) as response:
                append=response.status==206 and response.headers.get('Content-Range','').startswith(f'bytes {offset}-')
                with target.open('ab' if append else 'wb') as output:
                    while chunk:=response.read(1024*1024): output.write(chunk)
            if target.stat().st_size<=offset: failures+=1
            else: failures=0
        except Exception as failure:
            print('RETRY',type(failure).__name__,flush=True)
            failures+=1
            time.sleep(2)
    if target.stat().st_size!=expected_size: raise RuntimeError('SDK下载未完整结束: '+filename)
    digest=hashlib.new(algorithm)
    with target.open('rb') as data:
        while chunk:=data.read(1024*1024): digest.update(chunk)
    if digest.hexdigest()!=expected: raise RuntimeError('SDK归档校验失败: '+filename)
    staging=root/'.tools'/('extract-'+name.replace(';','-'))
    staging.mkdir(exist_ok=True)
    with zipfile.ZipFile(target) as data:
        data.extractall(staging)
        for entry in data.infolist():
            mode=entry.external_attr>>16
            path=staging/entry.filename
            if stat.S_ISLNK(mode):
                link=data.read(entry).decode()
                if not (path.parent/link).resolve().is_relative_to(staging.resolve()): raise RuntimeError('SDK链接超出归档范围')
                path.unlink(missing_ok=True)
                path.symlink_to(link)
            elif mode:
                path.chmod(mode&0o777)
    children=list(staging.iterdir())
    source=children[0] if len(children)==1 and children[0].is_dir() else staging
    destination.parent.mkdir(parents=True,exist_ok=True)
    if destination.exists(): shutil.rmtree(destination)
    shutil.move(str(source),str(destination))
    print('INSTALLED',name,flush=True)
