#!/usr/bin/env python3
"""在明确选择的iOS模拟器验证本地图库、FTP入口和本机模拟相机，不操作实体iPhone"""
import argparse
import datetime
import json
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--device', help='专用测试模拟器的UDID')
args = parser.parse_args()
inventory = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', 'available', '--json'], text=True))
devices = [device for group in inventory['devices'].values() for device in group]
device = next((item for item in devices if item['udid'] == args.device), None) if args.device else next((item for item in devices if item['name'].startswith('FrameLink QA')), None)
if device is None:
    raise SystemExit('请先创建专用iPhone模拟器，并用--device指定UDID；默认仅查找名称以FrameLink QA开头的设备')
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
command = ['xcodebuild', '-project', 'ios/FrameLink.xcodeproj', '-scheme', 'FrameLink', '-configuration', 'Debug',
           '-destination', 'platform=iOS Simulator,id=' + device['udid'], '-derivedDataPath', 'ios/DerivedData',
           '-parallel-testing-enabled', 'NO', '-collect-test-diagnostics', 'never', '-test-timeouts-enabled', 'YES',
           '-default-test-execution-time-allowance', '120', '-resultBundlePath', f'artifacts/ios-ui-{stamp}.xcresult',
           'CODE_SIGNING_ALLOWED=YES', 'CODE_SIGN_IDENTITY=-', 'CODE_SIGN_STYLE=Manual', 'test']
print(f"仅验证模拟器：{device['name']} ({device['udid']})", flush=True)
raise SystemExit(subprocess.run(command, cwd=root).returncode)
