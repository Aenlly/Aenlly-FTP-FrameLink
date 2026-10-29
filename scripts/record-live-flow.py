#!/usr/bin/env python3
"""在独立模拟器记录真实安装版的调参、对焦、单张拍摄及回图库流程"""
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import time

root=pathlib.Path(__file__).resolve().parents[1]
adb=[str(root/'.tools/android-sdk/platform-tools/adb'),'-s','emulator-5580']
if 'FrameLinkQA'not in subprocess.check_output(adb+['emu','avd','name'],text=True):raise SystemExit('仅允许使用独立验收模拟器')
version=re.search(r'versionName\s*=\s*"([^"]+)"',(root/'android/app/build.gradle.kts').read_text()).group(1)
prefix=f'live-ui-{version}'
video='/sdcard/'+prefix+'-flow.mp4'
def shell(*args):return subprocess.check_output(adb+['shell',*args],text=True).strip()
started=datetime.datetime.now(datetime.timezone.utc).isoformat()
configuration={key:shell(*args)for key,args in {'size':['wm','size'],'density':['wm','density'],'fontScale':['settings','get','system','font_scale'],'navigationMode':['cmd','overlay','lookup','android','android:integer/config_navBarInteractionMode'],'api':['getprop','ro.build.version.sdk']}.items()}
pid=shell(f'nohup screenrecord --bit-rate 6000000 --time-limit 180 {video} >/dev/null 2>&1 </dev/null & echo $!')
if not pid.isdecimal():raise SystemExit('录屏未返回有效进程ID')
result=1
try:result=subprocess.run(['python3',str(root/'scripts/instrument.py'),'live-flow'],cwd=root).returncode
finally:
    subprocess.run(adb+['shell','kill','-2',pid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    for _ in range(25):
        if subprocess.run(adb+['shell','kill','-0',pid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:break
        time.sleep(.2)
target=root/'artifacts'/f'{prefix}-flow.mp4'
subprocess.run(adb+['pull',video,str(target)],check=True)
hashes={}
for package,path in {'app.framelink.camera':'android/app/build/outputs/apk/debug/app-debug.apk','app.framelink.camera.test':'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'}.items():
    value=hashlib.sha256((root/path).read_bytes()).hexdigest()
    installed=shell('pm','path',package).removeprefix('package:')
    if shell('sha256sum',installed).split()[0]!=value:raise SystemExit('实际安装包与候选包不同')
    hashes[package]=value
receipt={'version':version,'passed':result==0,'startedAt':started,'completedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'apkSha256':hashes['app.framelink.camera'],'diagnosticSha256':hashes['app.framelink.camera.test'],'configuration':configuration,'video':{'file':target.name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()},'log':'build-logs/android-instrument-live-flow.log','physicalCameraVerified':False}
(root/'artifacts'/f'{prefix}-flow-evidence.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2))
raise SystemExit(result)
