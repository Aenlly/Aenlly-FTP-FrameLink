#!/usr/bin/env python3
"""冻结已通过本地检查的开发测试包，保留真机和发布待验收状态"""
import datetime
import hashlib
import json
import pathlib
import shutil
import struct
import subprocess
import zipfile

root=pathlib.Path(__file__).resolve().parents[1]
artifacts=root/'artifacts'
version='0.1.13'
ios_version='0.1.6'
checks=['cloud','web','bridge','upstream','android-tests-build','android-lint','android-instrument','android-instrument-ui','android-instrument-gallery','android-instrument-usability','android-instrument-live','android-visual-phone','android-visual-small','android-visual-gesture','android-identity-small','ftp-benchmark','ios','ios-core','ios-ui','ios-device']
checks.append('android-instrument-live-flow')
for name in checks:
    result=root/'build-logs'/f'{name}.exit'
    if not result.exists() or result.read_text().strip()!='0': raise SystemExit(f'检查尚未通过：{name}')
e2e=json.loads((artifacts/'web-e2e-results.json').read_text())
if not e2e.get('passed'): raise SystemExit('网页端到端检查未通过')
apk=artifacts/f'FrameLink-{version}-debug.apk'
shutil.copyfile(root/'android/app/build/outputs/apk/debug/app-debug.apk',apk)
tools=root/'.tools/android-sdk/build-tools/36.0.0'
certificate=subprocess.check_output([str(tools/'apksigner'),'verify','--print-certs',str(apk)],text=True)
diagnostics=artifacts/f'FrameLink-{version}-diagnostics.apk'
shutil.copyfile(root/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',diagnostics)
diagnostic_certificate=subprocess.check_output([str(tools/'apksigner'),'verify','--print-certs',str(diagnostics)],text=True)
def digest_line(value):
    return next(line for line in value.splitlines() if 'certificate SHA-256 digest:' in line)
if digest_line(certificate)!=digest_line(diagnostic_certificate): raise SystemExit('主包与诊断组件签名不一致')
previous=artifacts/'FrameLink-0.1.3-debug.apk'
if previous.exists():
    old_certificate=subprocess.check_output([str(tools/'apksigner'),'verify','--print-certs',str(previous)],text=True)
    if digest_line(certificate)!=digest_line(old_certificate): raise SystemExit('开发包签名变化，不能用于覆盖安装')
alignment=subprocess.run([str(tools/'zipalign'),'-c','-P','16','4',str(apk)],capture_output=True,text=True)
if alignment.returncode: raise SystemExit(alignment.stdout+alignment.stderr)
with zipfile.ZipFile(apk) as package:
    for name in package.namelist():
        if not name.startswith('lib/') or not name.endswith('.so'): continue
        data=package.read(name)
        if data[:5]!=b'\x7fELF\x02': raise SystemExit('包含不受支持的32位原生库：'+name)
        offset=struct.unpack_from('<Q',data,32)[0]
        step,count=struct.unpack_from('<HH',data,54)
        for index in range(count):
            at=offset+index*step
            if struct.unpack_from('<I',data,at)[0]!=1: continue
            position,address=struct.unpack_from('<QQ',data,at+8)
            page=struct.unpack_from('<Q',data,at+48)[0]
            if page<16384 or position%page!=address%page: raise SystemExit('原生库不满足16KiB段对齐：'+name)
files=subprocess.check_output(['git','ls-files','--cached'],cwd=root,text=True).splitlines()
source=artifacts/f'FrameLink-{version}-source.zip'
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as archive:
    for relative in files:
        path=root/relative
        if path.is_file(): archive.write(path,'camera-assistant/'+relative)
result={'version':version,'qualification':'development-preview','package':'app.framelink.camera','apk':apk.name,'apkBytes':apk.stat().st_size,'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'signing':'Android Debug certificate','certificate':certificate.strip(),'zip16KBAlignment':True,'checks':{name:0 for name in checks},'checkLogUpdatedAt':{name:datetime.datetime.fromtimestamp((root/'build-logs'/f'{name}.log').stat().st_mtime,datetime.timezone.utc).isoformat() for name in checks},'webE2eCases':len(e2e['steps']),'visualSamples':'licensed-real-photography','syntheticProtocolTest':True,'sonyA7CIIHardwareVerified':False,'bluetoothHardwareVerified':False,'productionPublished':False,'sourceArchive':source.name,'frozenAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
result['diagnosticApk']={'file':diagnostics.name,'sha256':hashlib.sha256(diagnostics.read_bytes()).hexdigest()}
ios=artifacts/f'FrameLink-{ios_version}-ios-simulator.zip'
if not ios.exists(): raise SystemExit('缺少iOS模拟器包')
result['ios']={'version':ios_version,'simulatorArchive':ios.name,'bytes':ios.stat().st_size,'sha256':hashlib.sha256(ios.read_bytes()).hexdigest(),'signing':'local-ad-hoc','physicalIPhoneVerified':False,'uiCases':5,'coreTests':6,'coreChecks':14}
ipa=artifacts/f'FrameLink-{ios_version}-ios-unsigned.ipa'
if not ipa.exists(): raise SystemExit('缺少供自签试装的iPhone IPA')
result['ios']['unsignedIpa']={'file':ipa.name,'sha256':hashlib.sha256(ipa.read_bytes()).hexdigest(),'needsResigning':True,'physicalIPhoneVerified':False}
result['hardwareEvidence']={'latestGlassScenePhysicalVerified':False}
evidence=artifacts/f'hardware-acceptance-{version}.json'
if evidence.exists():
    verified=json.loads(evidence.read_text())
    if verified.get('apkSha256')==result['apkSha256']: result['hardwareEvidence']=verified
result['sourceArchiveSha256']=hashlib.sha256(source.read_bytes()).hexdigest()
result['sourceCommit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
result['sourceWorktreeDirty']=bool(subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip())
result['elf16KBAlignment']=True
ui_evidence=artifacts/f'camera-ui-{version}-evidence.json'
if ui_evidence.exists():
    ui=json.loads(ui_evidence.read_text())
    if ui.get('apkSha256')==result['apkSha256']: result['cameraUiEvidence']=ui
gallery_evidence=artifacts/f'gallery-ui-{version}-evidence.json'
if gallery_evidence.exists():
    gallery=json.loads(gallery_evidence.read_text())
    if not gallery.get('passed') or gallery.get('apkSha256')!=result['apkSha256'] or gallery.get('diagnosticSha256')!=result['diagnosticApk']['sha256']:
        raise SystemExit('图库连续操作证据未通过或不属于当前主包、诊断组件')
    result['galleryUiEvidence']=gallery
alias=artifacts/f'FrameLink-{version}-glass-{datetime.datetime.now().strftime("%Y%m%d-%H%M")}-debug.apk'
shutil.copyfile(apk,alias)
result['timestampedApk']=alias.name
(artifacts/'artifact-manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(str(apk))
print('SHA256 '+result['apkSha256'])
print(str(source))
