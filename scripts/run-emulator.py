#!/usr/bin/env python3
"""启动本项目独立、无窗口的Android验收设备"""
import os
import pathlib
import subprocess
import sys

root=pathlib.Path(__file__).resolve().parents[1]
sdk=root/'.tools/android-sdk'
env=os.environ.copy()
env['JAVA_HOME']='/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home'
env['ANDROID_HOME']=str(sdk)
env['ANDROID_AVD_HOME']=str(root/'.tools/avd')
env['ANDROID_USER_HOME']=str(root/'.tools/android-user')
pathlib.Path(env['ANDROID_AVD_HOME']).mkdir(exist_ok=True)
pathlib.Path(env['ANDROID_USER_HOME']).mkdir(exist_ok=True)
tablet=len(sys.argv)>1 and sys.argv[1]=='tablet'
if len(sys.argv)>1 and not tablet:raise SystemExit('usage: run-emulator.py [tablet]')
name='FrameLinkTabletQA' if tablet else 'FrameLinkQA'
width,height,density=('2560','1600','320')if tablet else('1080','2400','420')
target=root/'.tools/avd'/f'{name}.avd'
log=(root/'build-logs'/('tablet-emulator.log'if tablet else'emulator.log')).open('a')
if not target.exists():
    image=sdk/'system-images/android-35/default/arm64-v8a'
    if not (image/'system.img').exists(): raise SystemExit('请先安装独立验收系统镜像')
    target.mkdir()
    (pathlib.Path(env['ANDROID_AVD_HOME'])/f'{name}.ini').write_text('avd.ini.encoding=UTF-8\npath='+str(target)+'\ntarget=android-35\n')
    values={'AvdId':name,'avd.ini.displayname':name,'abi.type':'arm64-v8a','hw.cpu.arch':'arm64','hw.cpu.ncore':'2','hw.ramSize':'2048','hw.lcd.width':width,'hw.lcd.height':height,'hw.lcd.density':density,'hw.gpu.enabled':'yes','hw.gpu.mode':'swiftshader','hw.keyboard':'yes','hw.mainKeys':'no','hw.battery':'yes','disk.dataPartition.size':'2G','image.sysdir.1':str(image),'tag.id':'default','target':'android-35','skin.name':width+'x'+height,'skin.path':'_no_skin','vm.heapSize':'256','fastboot.forceColdBoot':'yes'}
    (target/'config.ini').write_text(''.join(f'{key}={value}\n' for key,value in values.items()))
command=[str(sdk/'emulator/emulator'),'-avd',name,'-port','5582'if tablet else'5580','-no-window','-no-audio','-no-snapshot','-no-boot-anim','-gpu','swiftshader','-memory','2048','-cores','2']
(root/'build-logs'/('tablet-emulator.pid'if tablet else'emulator.pid')).write_text(str(os.getpid()))
raise SystemExit(subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT).returncode)
