#!/usr/bin/env python3
"""仅整理本工程Android源码格式，不处理third_party或全局配置"""
import hashlib
import pathlib
import subprocess
import urllib.request

root=pathlib.Path(__file__).resolve().parents[1]
url='https://repo.maven.apache.org/maven2/com/facebook/ktfmt/0.54/ktfmt-0.54-jar-with-dependencies.jar'
jar=root/'.tools/ktfmt-0.54.jar'
if not jar.exists(): urllib.request.urlretrieve(url,jar)
expected=urllib.request.urlopen(url+'.sha1').read().decode().split()[0]
if hashlib.sha1(jar.read_bytes()).hexdigest()!=expected: raise SystemExit('格式工具归档校验失败')
files=sorted((root/'android/app/src').rglob('*.kt'))
result=subprocess.run(['/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java','-jar',str(jar),'--kotlinlang-style',*[str(file) for file in files]])
raise SystemExit(result.returncode)
