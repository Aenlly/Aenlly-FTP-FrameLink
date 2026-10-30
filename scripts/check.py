#!/usr/bin/env python3
"""运行本项目检查并保留完整日志和真实退出码"""
import os
import pathlib
import subprocess
import sys
import datetime

root = pathlib.Path(__file__).resolve().parents[1]
label = sys.argv[1]
env = os.environ.copy()
env['JAVA_HOME'] = env.get('JAVA_HOME', '/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home')
env['ANDROID_HOME'] = str(root / '.tools/android-sdk')
env['ANDROID_NDK_HOME'] = str(root / '.tools/android-sdk/ndk/27.2.12479018')
env['GOPATH'] = str(root / '.tools/go-tools')
env['GOBIN'] = str(root / '.tools/go-tools/bin')
env['PATH'] = os.pathsep.join([str(root / '.tools/go/bin'), env['GOBIN'], str(root / '.tools/android-sdk/platform-tools'), env['JAVA_HOME'] + '/bin', env['PATH']])
checks = {
    'cloud': ('cloud', [['mvn', '-B', 'test']]),
    'cloud-package': ('cloud', [['mvn', '-B', 'package', '-DskipTests']]),
    'bridge': ('camera-bridge', [['go', 'mod', 'tidy'], ['go', 'test', './...']]),
    'bridge-race': ('camera-bridge', [['go','test','-race','./bridge','-timeout','90s']]),
    'upstream': ('third_party/alpha2go', [['go', 'test', './...']]),
    'android': ('.', [['sh', 'scripts/build-android.sh']]),
    'android-deps': ('android', [['./gradlew', '--no-daemon', ':app:dependencies', '--configuration', 'debugCompileClasspath']]),
    'android-tests-build': ('android', [['./gradlew','--no-daemon','testDebugUnitTest','assembleDebug','assembleDebugAndroidTest']]),
    'android-lint': ('android', [['./gradlew','--no-daemon','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx1536m','lintDebug']]),
    'ios-core': ('.', [['swift','test','--package-path','ios'], ['swift','run','--package-path','ios','FrameLinkCoreCheck']]),
    'ios': ('.', [['sh','scripts/build-ios.sh']]),
    'ios-ui': ('.', [[sys.executable,'scripts/check-ios-ui.py']]),
    'ios-device': ('.', [['xcodebuild','-project','ios/FrameLink.xcodeproj','-scheme','FrameLink','-configuration','Debug','-sdk','iphoneos','-destination','generic/platform=iOS','-derivedDataPath','ios/DerivedData-device','CODE_SIGNING_ALLOWED=NO','build']]),
    'ftp-benchmark': ('.', [[sys.executable, 'scripts/benchmark-ftp.py', '32', '7']]),
    'web': ('web', [['npm', 'ci'], ['npm', 'run', 'build'], ['npm', 'test', '--', '--run']]),
    'sdk': ('.', [[str(root / '.tools/android-sdk/cmdline-tools/latest/bin/sdkmanager'), '--sdk_root=' + env['ANDROID_HOME'], '--licenses'], [str(root / '.tools/android-sdk/cmdline-tools/latest/bin/sdkmanager'), '--sdk_root=' + env['ANDROID_HOME'], 'platform-tools', 'platforms;android-37.0', 'build-tools;37.0.0', 'ndk;27.2.12479018']]),
}
cwd, commands = checks[label]
logs = root / 'build-logs'
logs.mkdir(exist_ok=True)
status = logs / (label + '.exit')
if (logs / (label + '.log')).exists():
    stamp=datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    (logs / (label + '.log')).rename(logs / (label + '-' + stamp + '.log'))
    if status.exists(): status.rename(logs / (label + '-' + stamp + '.exit'))
status.unlink(missing_ok=True)
(logs / (label + '.pid')).write_text(str(os.getpid()))
result = 0
with (logs / (label + '.log')).open('w') as log:
    for command in commands:
        log.write('COMMAND: ' + ' '.join(command) + '\n')
        log.flush()
        result = subprocess.run(command, cwd=root / cwd, env=env, input=b'y\n' * 1000 if '--licenses' in command else None, stdout=log, stderr=subprocess.STDOUT).returncode
        if result:
            break
status.write_text(str(result))
print(f'{label}: exit={result}; log={logs / (label + ".log")}')
sys.exit(result)
