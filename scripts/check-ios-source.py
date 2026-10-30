#!/usr/bin/env python3
"""使用已安装SDK及真实Go生成头文件做iOS源码检查；不启动Xcode安装器或模拟器，不生成可运行App"""
import datetime
import hashlib
import json
import os
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
developer = pathlib.Path(os.environ.get('DEVELOPER_DIR', '/Applications/Xcode.app/Contents/Developer'))
sdk = developer / 'Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk'
swift = developer / 'Toolchains/XcodeDefault.xctoolchain/usr/bin/swiftc'
directory = root / 'artifacts' / ('ios-source-check-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
directory.mkdir(parents=True)
receipt = {'qualification': 'source-typecheck-only', 'appLinked': False, 'simulatorExecuted': False,
           'physicalIPhoneVerified': False, 'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
           'sourceWorktreeDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root).strip()),
           'sdk': str(sdk), 'compiler': str(swift), 'steps': []}
receipt['compilerVersion'] = subprocess.check_output([str(swift), '--version'], text=True).strip()
def save():
    (directory / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
def execute(name, command, cwd, env=None):
    log = directory / (name + '.log')
    with log.open('w') as output:
        output.write('COMMAND=' + json.dumps(command) + '\n'); output.flush()
        result = subprocess.run(command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT).returncode
    receipt['steps'].append({'name': name, 'exit': result, 'log': log.name, 'sha256': hashlib.sha256(log.read_bytes()).hexdigest()}); save()
    if result:
        print('源码检查未通过：' + str(log)); raise SystemExit(result)
print('源码检查归档：' + str(directory), flush=True); save()
env = os.environ.copy(); tools = root / '.tools/go-tools'
env.update({'GOPATH': str(tools), 'GOBIN': str(tools / 'bin'), 'GOPROXY': 'off', 'GOTOOLCHAIN': 'local',
            'PATH': str(root / '.tools/go/bin') + os.pathsep + str(tools / 'bin') + os.pathsep + env['PATH']})
bindings = directory / 'bindings'
execute('gobind', [str(tools / 'bin/gobind'), '-lang=objc', '-prefix=FL', '-outdir=' + str(bindings), './bridge'], root / 'camera-bridge', env)
headers = bindings / 'src/gobind'
module = directory / 'Camera'; module.mkdir()
(module / 'module.modulemap').write_text('module Camera {\n header ' + json.dumps(str(headers / 'FLBridge.objc.h')) + '\n header ' + json.dumps(str(headers / 'Universe.objc.h')) + '\n export *\n}\n')
modules = directory / 'modules'; modules.mkdir()
common = [str(swift), '-swift-version', '5', '-target', 'arm64-apple-ios17.0-simulator', '-sdk', str(sdk)]
execute('core-module', common + ['-emit-module', '-parse-as-library', '-module-name', 'FrameLinkCore', '-emit-module-path', str(modules / 'FrameLinkCore.swiftmodule')] + [str(path) for path in sorted((root / 'ios/Sources/FrameLinkCore').glob('*.swift'))], root)
execute('app-typecheck', common + ['-typecheck', '-parse-as-library', '-D', 'DEBUG', '-module-name', 'FrameLink', '-I', str(modules), '-I', str(module)] + [str(path) for path in sorted((root / 'ios/App').glob('*.swift'))], root)
execute('ui-typecheck', common + ['-typecheck', '-F', str(developer / 'Platforms/iPhoneSimulator.platform/Developer/Library/Frameworks'), '-I', str(developer / 'Platforms/iPhoneSimulator.platform/Developer/usr/lib'), str(root / 'ios/UITests/FrameLinkUITests.swift')], root)
receipt['passed'] = True
receipt['headerSha256'] = hashlib.sha256((headers / 'FLBridge.objc.h').read_bytes()).hexdigest(); save()
print('仅源码类型检查通过；Go二进制框架、App链接及UI运行仍需独立验证')
