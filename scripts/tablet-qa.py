#!/usr/bin/env python3
"""仅在独立平板模拟器验证窗口布局，不把显示尺寸模拟写成真实系统分屏结果"""
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys

root=pathlib.Path(__file__).resolve().parents[1]
adb=[str(root/'.tools/android-sdk/platform-tools/adb'),'-s','emulator-5582']
mode=sys.argv[1]if len(sys.argv)>1 else'all'
if mode not in ['all','matrix','state','freeform']:raise SystemExit('usage: tablet-qa.py all|matrix|state|freeform')
if 'FrameLinkTabletQA' not in subprocess.check_output(adb+['emu','avd','name'],text=True):raise SystemExit('不是独立平板验收模拟器')

def run(args,**kwargs):return subprocess.run(adb+args,check=True,**kwargs)
def shell(*args):return run(['shell',*args],stdout=subprocess.DEVNULL)

main=root/'android/app/build/outputs/apk/debug/app-debug.apk'
diagnostic=root/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
run(['install','-r',str(main)],stdout=subprocess.DEVNULL)
run(['install','-r',str(diagnostic)],stdout=subprocess.DEVNULL)
samples=root/'artifacts/visual-fixtures'
if len(list(samples.glob('*.jpg')))!=6:raise SystemExit('请先运行visual-fixtures.py准备授权样片')
folder='files/visual-fixtures'
shell('run-as','app.framelink.camera','mkdir','-p',folder)
for photo in samples.glob('*.jpg'):
    temporary='/data/local/tmp/FrameLinkTabletQA-'+photo.name
    run(['push',str(photo),temporary],stdout=subprocess.DEVNULL)
    shell('run-as','app.framelink.camera','cp',temporary,folder+'/'+photo.name)
    shell('rm',temporary)
shell('settings','put','secure','immersive_mode_confirmations','confirmed')
shell('settings','put','secure','show_ime_with_hard_keyboard','1')
shell('settings','put','global','enable_freeform_support','1')
shell('settings','put','global','force_resizable_activities','1')
shell('cmd','overlay','enable-exclusive','--category','com.android.internal.systemui.navbar.gestural')
profiles=[]
if mode in ['all','matrix']:
    profiles+=[('11-landscape',2560,1600,'1.0'),('11-portrait',1600,2560,'1.0'),('14-landscape',3200,2000,'1.0'),('14-portrait',2000,3200,'1.0'),('half-window',1280,1600,'1.0'),('third-window',852,1600,'1.0'),('large-font',2560,1600,'1.3')]
if mode in ['all','state']:profiles+=[('state',2560,1600,'1.0')]
if mode in ['all','freeform']:profiles+=[('freeform',2560,1600,'1.0')]
reports=[]
try:
    for name,width,height,font in profiles:
        shell('wm','size',f'{width}x{height}')
        shell('wm','density','320')
        shell('settings','put','system','font_scale',font)
        shell('am','start','-f','0x20000000','--windowingMode','1','-n','app.framelink.camera/.MainActivity')
        label='android-tablet-'+name
        log=root/'build-logs'/f'{label}.log'
        if log.exists():log.rename(log.with_name(label+'-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'.log'))
        method='realFreeformWindowKeepsPhotoSelectionScrollAndImportedTask'if name=='freeform'else'wideGalleryKeepsPreviewSelectionAndDialogStateThroughResizing'if name=='state'else'configuredWindowUsesAvailableSpace'
        command=['shell','am','instrument','-w','-r','-e','class','app.framelink.camera.TabletGalleryAcceptanceTest#'+method,'-e','tabletQa','true','-e','tabletVariant',name,'app.framelink.camera.test/androidx.test.runner.AndroidJUnitRunner']
        with log.open('w')as output:result=subprocess.run(adb+command,stdout=output,stderr=subprocess.STDOUT,timeout=300).returncode
        text=log.read_text()
        system=subprocess.check_output(adb+['logcat','-d','-v','raw','-s','System.out:I'],text=True)
        frames=[json.loads(line.split('TABLET_FRAME=',1)[1])for line in system.splitlines()if'TABLET_FRAME='in line]
        frames=[frame for frame in frames if frame.get('variant')==name]
        if frames:frames=[frame for frame in frames if frame.get('run')==frames[-1]['run']]
        with log.open('a')as output:
            for frame in frames:output.write('\nTABLET_FRAME='+json.dumps(frame,ensure_ascii=False)+'\n')
        if 'OK ('not in text or'FAILURES!!!'in text:result=result or 1
        if not frames:result=result or 1
        (root/'build-logs'/f'{label}.exit').write_text(str(result))
        report={'profile':name,'displayPixels':[width,height],'densityDpi':320,'fontScale':font,'passed':result==0,'log':str(log.relative_to(root)),'frames':frames}
        reports.append(report)
        print(name,'exit='+str(result),flush=True)
        if result:break
finally:
    shell('wm','size','reset');shell('wm','density','reset');shell('settings','put','system','font_scale','1.0')
    shot_dir='/sdcard/Android/data/app.framelink.camera/files/qa-shots/'
    listing=subprocess.run(adb+['shell','ls',shot_dir],capture_output=True,text=True)
    names=listing.stdout.splitlines()if listing.returncode==0 else[]
    for name in names:
        if name.startswith('tablet-')and name.endswith('.png'):run(['pull',shot_dir+name,str(root/'artifacts'/name)],stdout=subprocess.DEVNULL)
    screenshots=[{'file':name,'sha256':hashlib.sha256((root/'artifacts'/name).read_bytes()).hexdigest()}for name in names if name.startswith('tablet-')and name.endswith('.png')]
    receipt={'passed':len(reports)==len(profiles)and all(r['passed']for r in reports),'collectedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'apkSha256':hashlib.sha256(main.read_bytes()).hexdigest(),'diagnosticSha256':hashlib.sha256(diagnostic.read_bytes()).hexdigest(),'profiles':reports,'screenshots':screenshots,'windowMethod':'freeform task bounds'if mode=='freeform'else'display-size matrix plus freeform task bounds'if mode=='all'else'display-size simulation','pending':['系统双应用分屏操作','小米平板7及更大平板真机']}
    (root/'artifacts'/f'tablet-qa-{mode}-evidence.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2))
raise SystemExit(0 if receipt['passed']else 1)
