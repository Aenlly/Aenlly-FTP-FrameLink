#!/usr/bin/env python3
"""保存同包取景及两类工具菜单，记录普通或大字配置，仅允许本项目验收模拟器"""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import shutil
import struct
import subprocess

root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--theme',choices=['bright','dark'],required=True)
parser.add_argument('--variant',choices=['regular','large'],default='regular')
args=parser.parse_args()
adb=[str(root/'.tools/android-sdk/platform-tools/adb'),'-s','emulator-5580']
name=subprocess.check_output(adb+['emu','avd','name'],text=True).strip().splitlines()[0]
if name!='FrameLinkQA':raise SystemExit('不是独立验收模拟器')
version=re.search(r'versionName\s*=\s*"([^"]+)"',(root/'android/app/build.gradle.kts').read_text()).group(1)
status=root/('build-logs/android-instrument-live-menu.exit' if args.variant=='large' else 'build-logs/android-instrument-live.exit')
full_status=root/'build-logs/android-instrument.exit'
if args.variant=='regular' and full_status.exists() and (not status.exists() or full_status.stat().st_mtime>status.stat().st_mtime):status=full_status
if not status.exists() or status.read_text().strip()!='0':raise SystemExit('取景验收尚未通过')
sdk=int(subprocess.check_output(adb+['shell','getprop','ro.build.version.sdk'],text=True).strip())
packages={'app.framelink.camera':'android/app/build/outputs/apk/debug/app-debug.apk',
          'app.framelink.camera.test':'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'}
digests={}
for package,path in packages.items():
    expected=hashlib.sha256((root/path).read_bytes()).hexdigest()
    installed=subprocess.check_output(adb+['shell','pm','path',package],text=True).strip().removeprefix('package:')
    actual=subprocess.check_output(adb+['shell','sha256sum',installed],text=True).split()[0]
    if expected!=actual:raise SystemExit('模拟器安装包与候选构建不一致：'+package)
    digests[package]=expected
font_scale=float(subprocess.check_output(adb+['shell','settings','get','system','font_scale'],text=True).strip())
if args.variant=='large' and font_scale<1.3:raise SystemExit('大字证据必须使用至少1.3倍字号')
if args.variant=='regular' and font_scale>1.05:raise SystemExit('普通字号证据配置不一致')
directory=root/'artifacts'/('camera-ui-'+version+'-'+args.theme+('-large' if args.variant=='large' else ''))
directory.mkdir(exist_ok=True)
test_log=status.with_suffix('.log')
if not test_log.exists() or 'OK (' not in test_log.read_text():raise SystemExit('缺少本轮完整通过日志')
shutil.copyfile(test_log,directory/'test-run.log')
states={'portrait-photo':'live-portrait-camera','portrait-pro':'live-portrait-professional',
        'portrait-ruler':'live-portrait-ruler','landscape-photo':'live-landscape-photo',
        'landscape-pro':'live-landscape-camera','landscape-ruler':'live-landscape-ruler',
        'portrait-menu':'live-menu-portrait-body','landscape-menu':'live-menu-landscape-body',
        'portrait-assist-menu':'live-menu-portrait-assist','landscape-assist-menu':'live-menu-landscape-assist',
        'timer-3':'live-timer-3','timer-10':'live-timer-10'}
if args.variant=='large':states={key:value for key,value in states.items() if 'menu' in key or 'timer' in key}
images={}
for state,source in states.items():
    target=directory/(state+'.png')
    subprocess.run(adb+['pull','/sdcard/Android/data/app.framelink.camera/files/qa-shots/'+source+'.png',str(target)],check=True,capture_output=True)
    data=target.read_bytes()
    if data[:8]!=b'\x89PNG\r\n\x1a\n':raise SystemExit('截图无效：'+state)
    images[state]={'file':target.name,'dimensions':struct.unpack('>II',data[16:24]),'sha256':hashlib.sha256(data).hexdigest()}
receipt={'version':version,'packages':digests,'emulator':name,'api':sdk,'background':args.theme,
         'variant':args.variant,'fontScale':font_scale,'testStatus':str(status.relative_to(root)),
         'testLog':{'file':'test-run.log','sha256':hashlib.sha256((directory/'test-run.log').read_bytes()).hexdigest()},
         'preview':'camera protocol simulator','physicalCameraVerified':False,
         'capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'images':images}
(directory/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2))
print(str(directory))
print('screenshots='+str(len(images))+'; apkSha256='+digests['app.framelink.camera'])
