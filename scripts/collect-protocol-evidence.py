#!/usr/bin/env python3
"""核对独立模拟器的多协议安装版与完整回归，保留硬件待验边界"""
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

root=pathlib.Path(__file__).resolve().parents[1]
artifacts=root/'artifacts'
version=re.search(r'versionName\s*=\s*"([^"]+)"',(root/'android/app/build.gradle.kts').read_text()).group(1)
adb=[str(root/'.tools/android-sdk/platform-tools/adb'),'-s','emulator-5580']
if 'FrameLinkQA'not in subprocess.check_output(adb+['emu','avd','name'],text=True):raise SystemExit('仅允许独立FrameLinkQA模拟器')
labels=['bridge','bridge-race','android-tests-build','android-lint','android-instrument']
for label in labels:
    path=root/'build-logs'/f'{label}.exit'
    if not path.exists()or path.read_text().strip()!='0':raise SystemExit('检查未通过：'+label)
text=(root/'build-logs/android-instrument.log').read_text()
found=re.search(r'OK \((\d+) tests?\)',text)
if not found or'FAILURES!!!'in text:raise SystemExit('整轮安装版回归未通过')
cases={'android-instrument':int(found.group(1)),'android-instrument-canon':0,'android-instrument-usb':0}
current_class=''
for line in text.splitlines():
    if line.startswith('INSTRUMENTATION_STATUS: class='):current_class=line.split('=',1)[1]
    if line=='INSTRUMENTATION_STATUS_CODE: 0':
        if current_class.endswith('.CanonProtocolAcceptanceTest'):cases['android-instrument-canon']+=1
        elif current_class.endswith('.UsbConnectionUiAcceptanceTest'):cases['android-instrument-usb']+=1
if cases['android-instrument']<42 or cases['android-instrument-canon']<3 or cases['android-instrument-usb']<1:raise SystemExit('多协议用例不完整')
packages={}
for package,path in {'app.framelink.camera':'android/app/build/outputs/apk/debug/app-debug.apk','app.framelink.camera.test':'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'}.items():
    expected=hashlib.sha256((root/path).read_bytes()).hexdigest()
    installed=subprocess.check_output(adb+['shell','pm','path',package],text=True).strip().removeprefix('package:')
    actual=subprocess.check_output(adb+['shell','sha256sum',installed],text=True).split()[0]
    if actual!=expected:raise SystemExit('安装包与候选不一致：'+package)
    packages[package]=expected
directory=artifacts/f'protocol-{version}-shots'
directory.mkdir(exist_ok=True)
images=[]
for source,name in [('ccapi-connected-home.png','ccapi-connection-entry.png'),('ccapi-gallery-selection.png','ccapi-gallery-selection.png'),('usb-reader-entry-no-device.png','usb-reader-entry-no-device.png')]:
    target=directory/name
    subprocess.run(adb+['pull','/sdcard/Android/data/app.framelink.camera/files/qa-shots/'+source,str(target)],check=True,capture_output=True)
    data=target.read_bytes()
    if data[:8]!=b'\x89PNG\r\n\x1a\n':raise SystemExit('无效协议截图')
    images.append({'file':str(target.relative_to(artifacts)),'sha256':hashlib.sha256(data).hexdigest()})
unit=failures=0
for path in (root/'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    result=ET.parse(path).getroot()
    unit+=int(result.get('tests','0'))
    failures+=int(result.get('failures','0'))+int(result.get('errors','0'))
if failures or unit<46:raise SystemExit('单元测试数量或结果不符')
result={
    'version':version,'passed':True,'collectedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'apkSha256':packages['app.framelink.camera'],'diagnosticSha256':packages['app.framelink.camera.test'],
    'device':'FrameLinkQA emulator-5580','unitTests':unit,'instrumentCases':cases,
    'screenshots':images,'logs':['build-logs/'+label+'.log'for label in labels],'caseSource':'same installed all-class run',
    'scope':{
        'canon':'独立合成CCAPI服务；连接、媒体、JPEG/CR3字节校验、取景解码、点位+AF、单张保护、图库选片和原片任务、保存配置与直接重连',
        'sony':'既有协议回归及四个型号字段的共用后端合成矩阵；不等于各机型真实抓包或实测',
        'usb':'Android SDK适配器编译、媒体逻辑假设备测试和无设备入口；未执行真实USB握手或RAF传输',
    },
    'physicalCameraVerified':False,'physicalUsbVerified':False,'physicalPhoneVerified':False,
    'pending':['EOS850D固件与CCAPI实机','X-T30 II USB模式、RAF、断开恢复与吞吐','ILCE-7M4/7M5/6700实机兼容','机身色彩控制','富士无线遥控'],
}
path=artifacts/f'protocol-ui-{version}-evidence.json'
path.write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(str(path))
print('unitTests='+str(unit)+'; installedCases='+str(cases['android-instrument'])+'; screenshots='+str(len(images)))
