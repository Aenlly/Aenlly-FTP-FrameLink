#!/usr/bin/env python3
"""仅在本项目FrameLinkQA模拟器安装并执行验收测试"""
import pathlib
import subprocess
import sys
import datetime

root=pathlib.Path(__file__).resolve().parents[1]
adb=str(root/'.tools/android-sdk/platform-tools/adb')
serial='emulator-5580'
base=[adb,'-s',serial]
name=subprocess.check_output(base+['emu','avd','name'],text=True)
if 'FrameLinkQA' not in name: raise SystemExit('设备不是本项目验收模拟器，停止安装')
commands=[['reverse','tcp:15740','tcp:15740'],['reverse','tcp:16001','tcp:16001'],['install','-r',str(root/'android/app/build/outputs/apk/debug/app-debug.apk')],['install','-r',str(root/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')],['shell','am','instrument','-w','-r','app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner']]
if len(sys.argv)>1 and sys.argv[1]=='native': commands[-1]=['shell','am','instrument','-w','-r','-e','class','app.framelink.camera.NativeProtocolAcceptanceTest','app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner']
if len(sys.argv)>1 and sys.argv[1]=='ui': commands[-1]=['shell','am','instrument','-w','-r','-e','class','app.framelink.camera.NativeUiAcceptanceTest','app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner']
if len(sys.argv)>1 and sys.argv[1]=='ftp': commands[-1]=['shell','am','instrument','-w','-r','-e','class','app.framelink.camera.FtpClientAcceptanceTest','app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner']
if len(sys.argv)>1 and sys.argv[1]=='ftp-ui': commands[-1]=['shell','am','instrument','-w','-r','-e','class','app.framelink.camera.FtpClientAcceptanceTest#installedUiShowsCoreActionsConnectsAndDownloads','app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner']
log=root/'build-logs/android-instrument.log'
if log.exists():
    stamp=datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    log.rename(log.with_name('android-instrument-'+stamp+'.log'))
result=0
(root/'build-logs/android-instrument.exit').unlink(missing_ok=True)
with log.open('w') as out:
    for command in commands:
        result=subprocess.run(base+command,stdout=out,stderr=subprocess.STDOUT).returncode
        if result: break
text=log.read_text()
if 'FAILURES!!!' in text or 'INSTRUMENTATION_FAILED' in text or 'OK (' not in text: result=result or 1
(root/'build-logs/android-instrument.exit').write_text(str(result))
print(f'Android instrument exit={result}; log={log}')
sys.exit(result)
