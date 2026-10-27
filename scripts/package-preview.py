#!/usr/bin/env python3
"""冻结已通过本地检查的开发测试包，保留真机和发布待验收状态"""
import datetime
import hashlib
import json
import pathlib
import shutil
import subprocess
import zipfile

root=pathlib.Path(__file__).resolve().parents[1]
artifacts=root/'artifacts'
version='0.1.3'
checks=['cloud','web','bridge','upstream','android-tests-build','android-lint','android-instrument','android-visual-phone','android-visual-small','android-visual-gesture','android-identity-small','ftp-benchmark']
for name in checks:
    result=root/'build-logs'/f'{name}.exit'
    if not result.exists() or result.read_text().strip()!='0': raise SystemExit(f'检查尚未通过：{name}')
e2e=json.loads((artifacts/'web-e2e-results.json').read_text())
if not e2e.get('passed'): raise SystemExit('网页端到端检查未通过')
apk=artifacts/f'FrameLink-{version}-debug.apk'
shutil.copyfile(root/'android/app/build/outputs/apk/debug/app-debug.apk',apk)
tools=root/'.tools/android-sdk/build-tools/36.0.0'
certificate=subprocess.check_output([str(tools/'apksigner'),'verify','--print-certs',str(apk)],text=True)
alignment=subprocess.run([str(tools/'zipalign'),'-c','-P','16','4',str(apk)],capture_output=True,text=True)
if alignment.returncode: raise SystemExit(alignment.stdout+alignment.stderr)
files=subprocess.check_output(['git','ls-files','--cached'],cwd=root,text=True).splitlines()
source=artifacts/f'FrameLink-{version}-source.zip'
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as archive:
    for relative in files:
        path=root/relative
        if path.is_file(): archive.write(path,'camera-assistant/'+relative)
result={'version':version,'qualification':'development-preview','package':'app.framelink.camera','apk':apk.name,'apkBytes':apk.stat().st_size,'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'signing':'Android Debug certificate','certificate':certificate.strip(),'zip16KBAlignment':True,'checks':{name:0 for name in checks},'checkLogUpdatedAt':{name:datetime.datetime.fromtimestamp((root/'build-logs'/f'{name}.log').stat().st_mtime,datetime.timezone.utc).isoformat() for name in checks},'webE2eCases':len(e2e['steps']),'visualSamples':'licensed-real-photography','syntheticProtocolTest':True,'sonyA7CIIHardwareVerified':False,'bluetoothHardwareVerified':False,'productionPublished':False,'sourceArchive':source.name,'frozenAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(artifacts/'artifact-manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(str(apk))
print('SHA256 '+result['apkSha256'])
print(str(source))
