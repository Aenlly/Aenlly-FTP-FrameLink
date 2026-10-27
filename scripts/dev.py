#!/usr/bin/env python3
"""仅用于本机开发；公开部署使用cloud/README中的独立配置"""
import os
import pathlib
import subprocess
import sys

root=pathlib.Path(__file__).resolve().parents[1]
mode=sys.argv[1]
env=os.environ.copy()
env.setdefault('JAVA_HOME','/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home')
if mode=='cloud':
    env['FRAME_BIND_ADDRESS']='127.0.0.1'
    env.setdefault('FRAME_INVITE_CODE','local-dev-only')
    env.setdefault('FRAME_PUBLIC_URL','http://localhost:5177')
    command=[env['JAVA_HOME']+'/bin/java','-jar',str(root/'cloud/target/framelink-cloud-0.1.0.jar')]
    cwd=root/'cloud'
elif mode=='web':
    command=['npm','run','dev']
    cwd=root/'web'
elif mode=='simulator':
    command=[str(root/'.tools/go/bin/go'),'run','./cmd/simulator','-dir',str(root/'artifacts/fixtures')]
    cwd=root/'camera-bridge'
else:
    raise SystemExit('usage: dev.py cloud|web')
logs=root/'build-logs'
logs.mkdir(exist_ok=True)
with (logs/('dev-'+mode+'.log')).open('a') as log:
    raise SystemExit(subprocess.run(command,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT).returncode)
