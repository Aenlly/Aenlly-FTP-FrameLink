#!/usr/bin/env python3
"""按已校验SDK归档恢复符号链接，只处理本项目的工具目录"""
import pathlib
import stat
import sys
import zipfile

root=pathlib.Path(__file__).resolve().parents[1]
archive=pathlib.Path(sys.argv[1]).resolve()
destination=pathlib.Path(sys.argv[2]).resolve()
if not destination.is_relative_to(root/'.tools'): raise SystemExit('只允许处理项目工具目录')
count=0
with zipfile.ZipFile(archive) as src:
    for entry in src.infolist():
        if not stat.S_ISLNK(entry.external_attr>>16): continue
        path=destination.joinpath(*pathlib.PurePosixPath(entry.filename).parts[1:])
        link=src.read(entry).decode()
        if not (path.parent/link).resolve().is_relative_to(destination): raise RuntimeError('SDK链接超出包目录')
        path.unlink(missing_ok=True)
        path.symlink_to(link)
        count+=1
print('Restored SDK symlinks:',count)
