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
commands=[['shell','settings','put','secure','immersive_mode_confirmations','confirmed'],['reverse','tcp:15740','tcp:15740'],['reverse','tcp:18080','tcp:18080'],['reverse','tcp:16001','tcp:16001'],['install','-r',str(root/'android/app/build/outputs/apk/debug/app-debug.apk')],['install','-r',str(root/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')],['shell','am','instrument','-w','-r','app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner']]
mode=sys.argv[1] if len(sys.argv)>1 else 'all'
prefix='app.framelink.camera.'
classes={'all':','.join(prefix+x for x in ['LocalAcceptanceTest','NativeProtocolAcceptanceTest','NativeUiAcceptanceTest','FtpClientAcceptanceTest','PhotoManagementAcceptanceTest','CameraIdentityAcceptanceTest','GalleryInteractionAcceptanceTest','SelectionBatchesAcceptanceTest','LiveViewUsabilityAcceptanceTest','CameraUsabilityAcceptanceTest']), 'native':prefix+'NativeProtocolAcceptanceTest', 'ui':prefix+'NativeUiAcceptanceTest', 'ftp':prefix+'FtpClientAcceptanceTest', 'ftp-ui':prefix+'FtpClientAcceptanceTest#installedUiShowsCoreActionsConnectsAndDownloads', 'visual':prefix+'VisualAcceptanceTest','photos':prefix+'PhotoManagementAcceptanceTest','identity':prefix+'CameraIdentityAcceptanceTest','gallery':prefix+'GalleryInteractionAcceptanceTest','batches':prefix+'SelectionBatchesAcceptanceTest','live':prefix+'LiveViewUsabilityAcceptanceTest','usability':prefix+'CameraUsabilityAcceptanceTest'}
classes['live-flow']=prefix+'LiveViewUsabilityAcceptanceTest#cameraWorkflowStaysConnectedThroughRulerFocusShotAlbumAndRotation'
classes['live-menu']=prefix+'LiveViewUsabilityAcceptanceTest#compactMenusKeepBodyDefaultAndReadableTargetsAcrossRotation'
classes['canon']=prefix+'CanonProtocolAcceptanceTest'
classes['usb']=prefix+'UsbConnectionUiAcceptanceTest'
classes['usb-workflow']=prefix+'UsbWorkflowAcceptanceTest'
classes['fuji-autosave']=prefix+'FujiAutoSaveAcceptanceTest'
classes['protocol-targeted']=','.join(classes[key] for key in ['fuji-autosave','usb-workflow','native'])
classes['creative-look']=prefix+'CreativeLookAcceptanceTest'
classes['all']+=','+classes['canon']+','+classes['usb']
if mode not in classes: raise SystemExit('未知验收模式：'+mode)
if mode in ['all', 'identity', 'gallery', 'ui']:
    # 仅测试设备需要绑定反向端口22；业务App连接相机不需要root
    commands[0:0] = [['root'], ['wait-for-device'], ['reverse','tcp:22','tcp:16022'], ['reverse','tcp:16023','tcp:16023']]
variant=sys.argv[2] if len(sys.argv)>2 else 'phone'
if variant not in ['phone','small','gesture']: raise SystemExit('未知界面规格：'+variant)
commands[-1]=['shell','am','instrument','-w','-r','-e','class',classes[mode],'-e','visualVariant',variant,'app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner']
label='android-visual-'+variant if mode=='visual' else 'android-identity-'+variant if mode=='identity' else 'android-instrument' if mode=='all' else 'android-instrument-'+mode
log=root/'build-logs'/(label+'.log')
if log.exists():
    stamp=datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    log.rename(log.with_name(label+'-'+stamp+'.log'))
result=0
(root/'build-logs'/(label+'.exit')).unlink(missing_ok=True)
with log.open('w') as out:
    for command in commands:
        try:
            result=subprocess.run(base+command,stdout=out,stderr=subprocess.STDOUT,timeout=600).returncode
        except subprocess.TimeoutExpired:
            out.write('\n验收超时，仅停止独立FrameLinkQA的测试进程\n')
            subprocess.run(base+['shell','am','force-stop','app.framelink.camera'],stdout=out,stderr=subprocess.STDOUT)
            result=124
        if result: break
text=log.read_text()
if 'FAILURES!!!' in text or 'INSTRUMENTATION_FAILED' in text or 'OK (' not in text: result=result or 1
(root/'build-logs'/(label+'.exit')).write_text(str(result))
print(f'Android instrument exit={result}; log={log}')
sys.exit(result)
