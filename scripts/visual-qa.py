#!/usr/bin/env python3
"""只在FrameLinkQA检查授权样片、屏幕尺寸、字体与系统导航；退出时恢复测试设备配置"""
import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
adb = [str(root / '.tools/android-sdk/platform-tools/adb'), '-s', 'emulator-5580']
name = subprocess.check_output(adb + ['emu', 'avd', 'name'], text=True)
if 'FrameLinkQA' not in name:
    raise SystemExit('设备不是独立验收模拟器，停止操作')
variants = ['phone', 'small', 'gesture'] if len(sys.argv) < 2 or sys.argv[1] == 'all' else [sys.argv[1]]
if any(value not in ['phone', 'small', 'gesture'] for value in variants):
    raise SystemExit('usage: visual-qa.py all|phone|small|gesture')

def shell(*args):
    subprocess.run(adb + ['shell', *args], check=True, stdout=subprocess.DEVNULL)

def restore():
    shell('wm', 'size', 'reset')
    shell('wm', 'density', 'reset')
    shell('settings', 'put', 'system', 'font_scale', '1.0')
    shell('cmd', 'overlay', 'enable-exclusive', '--category', 'com.android.internal.systemui.navbar.threebutton')

subprocess.run([sys.executable, str(root / 'scripts/visual-fixtures.py')], check=True)
directory = '/sdcard/Android/data/app.framelink.camera/files/visual-fixtures'
shell('mkdir', '-p', directory)
for photo in (root / 'artifacts/visual-fixtures').glob('*.jpg'):
    subprocess.run(adb + ['push', str(photo), directory + '/' + photo.name], check=True, stdout=subprocess.DEVNULL)
try:
    for variant in variants:
        restore()
        if variant == 'small':
            shell('wm', 'size', '720x1280')
            shell('wm', 'density', '320')
            shell('settings', 'put', 'system', 'font_scale', '1.2')
        elif variant == 'gesture':
            shell('cmd', 'overlay', 'enable-exclusive', '--category', 'com.android.internal.systemui.navbar.gestural')
        result = subprocess.run([sys.executable, str(root / 'scripts/instrument.py'), 'visual', variant], cwd=root)
        if result.returncode == 0 and variant == 'small':
            result = subprocess.run([sys.executable, str(root / 'scripts/instrument.py'), 'identity', variant], cwd=root)
        shots = '/sdcard/Android/data/app.framelink.camera/files/qa-shots/'
        names = subprocess.check_output(adb + ['shell', 'ls', shots], text=True).splitlines()
        for name in names:
            if name.startswith('visual-' + variant + '-') and name.endswith('.png'):
                subprocess.run(adb + ['pull', shots + name, str(root / 'artifacts' / name)], check=True, stdout=subprocess.DEVNULL)
        if result.returncode:
            raise SystemExit(result.returncode)
finally:
    restore()
