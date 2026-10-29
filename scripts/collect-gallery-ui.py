#!/usr/bin/env python3
"""在独立FrameLinkQA模拟器记录图库连续操作，核对实际安装包并保存证据"""
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import time

root = pathlib.Path(__file__).resolve().parents[1]
adb = [str(root / '.tools/android-sdk/platform-tools/adb'), '-s', 'emulator-5580']
artifacts = root / 'artifacts'
version = re.search(r'versionName\s*=\s*"([^"]+)"', (root / 'android/app/build.gradle.kts').read_text()).group(1)
prefix = f'gallery-ui-{version}'

def output(*args):
    return subprocess.check_output(adb + list(args), text=True).strip()

def shell(*args):
    return output('shell', *args)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def installed_hash(package):
    path = shell('pm', 'path', package).removeprefix('package:')
    if not path.startswith('/data/app/') or not path.endswith('/base.apk'):
        raise RuntimeError('无法核对实际安装包：' + package)
    return shell('sha256sum', path).split()[0]

if 'FrameLinkQA' not in output('emu', 'avd', 'name'):
    raise SystemExit('仅允许使用本项目FrameLinkQA模拟器')
if not (artifacts / 'visual-fixtures/attribution.json').is_file():
    raise SystemExit('请先运行python3 scripts/visual-qa.py gesture准备授权样片')
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
previous_font = shell('settings', 'get', 'system', 'font_scale')
previous_nav = shell('cmd', 'overlay', 'lookup', 'android', 'android:integer/config_navBarInteractionMode')
video = '/sdcard/' + prefix + '-flow.mp4'
pid = None
result = 1
try:
    shell('settings', 'put', 'system', 'font_scale', '1.0')
    shell('cmd', 'overlay', 'enable-exclusive', '--category', 'com.android.internal.systemui.navbar.gestural')
    configuration = {
        'size': shell('wm', 'size'), 'density': shell('wm', 'density'),
        'fontScale': shell('settings', 'get', 'system', 'font_scale'),
        'navigationMode': shell('cmd', 'overlay', 'lookup', 'android', 'android:integer/config_navBarInteractionMode'),
        'api': shell('getprop', 'ro.build.version.sdk'),
    }
    pid = shell(f'nohup screenrecord --bit-rate 6000000 --time-limit 180 {video} >/dev/null 2>&1 </dev/null & echo $!')
    if not pid.isdecimal():
        raise RuntimeError('录屏未返回有效进程ID')
    result = subprocess.run(['python3', str(root / 'scripts/instrument.py'), 'visual', 'gesture'], cwd=root).returncode
finally:
    if pid and pid.isdecimal():
        subprocess.run(adb + ['shell', 'kill', '-2', pid], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(25):
            alive = subprocess.run(adb + ['shell', 'kill', '-0', pid], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if alive.returncode:
                break
            time.sleep(.2)
    shell('settings', 'put', 'system', 'font_scale', previous_font)
    shell('cmd', 'overlay', 'enable-exclusive', '--category', 'com.android.internal.systemui.navbar.gestural' if previous_nav == '2' else 'com.android.internal.systemui.navbar.threebutton')

subprocess.run(adb + ['pull', video, str(artifacts / (prefix + '-flow.mp4'))], check=True)
folder = '/sdcard/Android/data/app.framelink.camera/files/qa-shots/'
images = []
for name in shell('ls', folder).splitlines():
    if name.startswith('visual-gesture-') and name.endswith('.png'):
        path = artifacts / name
        subprocess.run(adb + ['pull', folder + name, str(path)], check=True, stdout=subprocess.DEVNULL)
        images.append({'file': name, 'sha256': digest(path)})
main = root / 'android/app/build/outputs/apk/debug/app-debug.apk'
diagnostic = root / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
main_hash, diagnostic_hash = digest(main), digest(diagnostic)
if installed_hash('app.framelink.camera') != main_hash or installed_hash('app.framelink.camera.test') != diagnostic_hash:
    raise SystemExit('实际安装包与候选包散列不一致，不能冻结证据')
evidence = {
    'version': version, 'passed': result == 0, 'device': 'FrameLinkQA emulator-5580',
    'startedAt': started, 'completedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'apkSha256': main_hash, 'diagnosticSha256': diagnostic_hash, 'configuration': configuration,
    'video': {'file': prefix + '-flow.mp4', 'sha256': digest(artifacts / (prefix + '-flow.mp4'))},
    'screenshots': images, 'log': 'build-logs/android-visual-gesture.log',
    'flow': ['图库浏览', '多选', '本地移除对话框并取消', '双图对比', '大图与缩放', '返回图库并检查末行', '项目', '传输', '设置'],
    'pending': ['新版手机真机覆盖安装与验收', '真实相机重连与选片复测', '完整平板布局与分屏验收', 'iOS新版图库'],
}
(artifacts / (prefix + '-evidence.json')).write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
raise SystemExit(result)
