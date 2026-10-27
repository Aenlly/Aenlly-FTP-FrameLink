#!/usr/bin/env python3
"""使用已编译的生产FTP数据流，在本机回环连接对比接收写入与指纹开销"""
import os
import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
classes = root / 'android/app/build/tmp/kotlin-classes/debug'
for name in ['FtpTransferIo', 'FtpConnection']:
    compiled = classes / f'app/framelink/camera/{name}.class'
    source = root / f'android/app/src/main/java/app/framelink/camera/{"FtpClient" if name == "FtpConnection" else name}.kt'
    if not compiled.is_file() or compiled.stat().st_mtime < source.stat().st_mtime:
        raise SystemExit('生产代码尚未编译或已变化，请先运行python3 scripts/check.py android-tests-build')
cache = pathlib.Path(os.environ.get('GRADLE_USER_HOME', pathlib.Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
classpath = [classes]
dependencies = [
    ('org.apache.ftpserver', 'ftpserver-core', '1.2.0'),
    ('org.apache.ftpserver', 'ftplet-api', '1.2.0'),
    ('org.apache.mina', 'mina-core', '2.1.6'),
    ('org.slf4j', 'slf4j-api', '1.7.36'),
    ('commons-net', 'commons-net', '3.13.0'),
    ('commons-io', 'commons-io', '2.21.0'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.20'),
]
for group, name, version in dependencies:
    jars = sorted(cache.glob(f'{group}/{name}/{version}/*/*.jar'))
    if not jars:
        raise SystemExit('Gradle缓存中缺少依赖，请先完成Android构建：' + name)
    classpath.extend(jars)
java_home = pathlib.Path(os.environ.get('JAVA_HOME', '/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home'))
command = [str(java_home / 'bin/java'), '--class-path', os.pathsep.join(map(str, classpath)), str(root / 'scripts/FtpIoBenchmark.java'), *sys.argv[1:]]
raise SystemExit(subprocess.run(command, cwd=root).returncode)
