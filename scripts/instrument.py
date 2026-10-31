#!/usr/bin/env python3
"""仅在本项目FrameLinkQA模拟器安装并执行验收测试"""
import pathlib
import subprocess
import sys
import datetime
import hashlib
import json
import re
import shutil
import uuid

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
classes['exif']=prefix+'PhotoExifAcceptanceTest'
classes['gallery-transitions']=','.join([
    prefix+'RetainedPhotoImageAcceptanceTest',
    prefix+'GalleryInteractionAcceptanceTest#filmstripSelectionDoesNotReturnToThePreviousPagerPhoto',
    prefix+'GalleryInteractionAcceptanceTest#viewerAndGridUseOneSelectionAndLegacyMarksDoNotSelectPhotos',
    prefix+'GalleryInteractionAcceptanceTest#browseModeKeepsSelectionVisibleInGridAndListWithoutSelectingOtherPhotos',
])
classes['foundations']=','.join(prefix+x for x in ['ConnectionAndHistoryAcceptanceTest','ConnectionHistoryUiAcceptanceTest'])
classes['live-direction']=prefix+'LiveDeviceOrientationAcceptanceTest'
classes['all']+=','+classes['canon']+','+classes['usb']
if mode not in classes: raise SystemExit('未知验收模式：'+mode)
if mode in ['all', 'identity', 'gallery', 'ui']:
    # 仅测试设备需要绑定反向端口22；业务App连接相机不需要root
    commands[0:0] = [['root'], ['wait-for-device'], ['reverse','tcp:22','tcp:16022'], ['reverse','tcp:16023','tcp:16023']]
variant=sys.argv[2] if len(sys.argv)>2 else 'phone'
if variant not in ['phone','small','gesture']: raise SystemExit('未知界面规格：'+variant)
commands[-1]=['shell','am','instrument','-w','-r','-e','class',classes[mode],'-e','visualVariant',variant,'app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner']
label='android-visual-'+variant if mode=='visual' else 'android-identity-'+variant if mode=='identity' else 'android-instrument' if mode=='all' else 'android-instrument-'+mode
shared_log=root/'build-logs'/(label+'.log')
if shared_log.exists():
    stamp=datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    shared_log.rename(shared_log.with_name(label+'-'+stamp+'.log'))
result=0
(root/'build-logs'/(label+'.exit')).unlink(missing_ok=True)
artifact_paths={'main':root/'android/app/build/outputs/apk/debug/app-debug.apk','diagnostics':root/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'}
digests={name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in artifact_paths.items()}
badging=subprocess.check_output([str(root/'.tools/android-sdk/build-tools/36.0.0/aapt'),'dump','badging',str(artifact_paths['main'])],text=True)
version=re.search(r"versionName='([^']+)'",badging).group(1)
expected=re.search(r'versionName\s*=\s*"([^"]+)"',(root/'android/app/build.gradle.kts').read_text()).group(1)
if version!=expected:raise SystemExit('主包版本与源码不符，请先构建')
run_id=uuid.uuid4().hex
directory=root/'artifacts'/f'FrameLink-{version}-evidence'/(label+'-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
directory.mkdir(parents=True,exist_ok=False)
log=directory/'test-run.log'
receipt={'version':version,'mode':mode,'serial':serial,'runId':run_id,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'apkSha256':digests['main'],'diagnosticSha256':digests['diagnostics'],'installedBeforeRun':False,'exit':None}
(directory/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2))
commands[-1][-1:-1]=['-e','qaRunTag',run_id]
print('Run archive: '+str(directory),flush=True)
try:
    with log.open('w') as out:
        out.write('RUN_ARTIFACTS='+json.dumps(receipt)+'\n');out.flush()
        for command in commands:
            if command[:3]==['shell','am','instrument']:
                for name,package in [('main','app.framelink.camera'),('diagnostics','app.framelink.camera.test')]:
                    location=subprocess.check_output(base+['shell','pm','path',package],text=True).strip().removeprefix('package:')
                    actual=subprocess.check_output(base+['shell','sha256sum',location],text=True).split()[0]
                    if actual!=digests[name]:raise RuntimeError('已安装包与本轮摘要不符：'+package)
                receipt['installedBeforeRun']=True
                out.write('INSTALLED_ARTIFACTS='+json.dumps(receipt)+'\n');out.flush()
            try:
                result=subprocess.run(base+command,stdout=out,stderr=subprocess.STDOUT,timeout=600).returncode
            except subprocess.TimeoutExpired:
                out.write('\n验收超时，仅停止独立FrameLinkQA的测试进程\n')
                subprocess.run(base+['shell','am','force-stop','app.framelink.camera'],stdout=out,stderr=subprocess.STDOUT)
                result=124
            if result:break
except Exception as error:
    result=1
    with log.open('a')as out:out.write('\nRunner error: '+str(error)+'\n')
text=log.read_text()
if 'FAILURES!!!' in text or 'INSTRUMENTATION_FAILED' in text or 'OK (' not in text: result=result or 1
(root/'build-logs'/(label+'.exit')).write_text(str(result))
shutil.copy2(log,shared_log)
receipt.update({'exit':result,'finishedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'logSha256':hashlib.sha256(log.read_bytes()).hexdigest(),'buildOutputsUnchanged':all(hashlib.sha256(path.read_bytes()).hexdigest()==digests[name]for name,path in artifact_paths.items())})
(directory/'exit.txt').write_text(str(result))
if mode=='foundations':
    shot_root='/sdcard/Android/data/app.framelink.camera/files/qa-shots/'
    listing=subprocess.run(base+['shell','ls',shot_root],capture_output=True,text=True)
    receipt['screenshots']=[]
    for name in listing.stdout.splitlines():
        if not name.endswith('-'+run_id+'.png'):continue
        target=directory/name
        pulled=subprocess.run(base+['pull',shot_root+name,str(target)],capture_output=True)
        if pulled.returncode==0:receipt['screenshots'].append({'file':name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
(directory/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2))
print(f'Android instrument exit={result}; log={log}')
sys.exit(result)
