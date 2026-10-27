# 协作审阅指南

本分支为光屿FrameLink 0.1.1开发测试版，基于Aenlly-FTP的`7bef1146052b3c9f343856bbf3952ee7236b14d1`保留上游历史。Android应用采用独立包名`app.framelink.camera`，工程入口迁至`android/`；原始项目源码和许可证保存在`third_party/aenlly-ftp/`

## 获取分支

```sh
git clone --branch feature/framelink-v1 https://github.com/Aenlly/Aenlly-FTP.git
cd Aenlly-FTP
```

## 建议审阅顺序

1. 阅读[实施与验收报告](IMPLEMENTATION.md)，区分已实现、本地已验证和真机待验收范围
2. 查看`android/`的四入口界面、本地数据与任务恢复，以及`camera-bridge/`的相机协议封装
3. 查看`cloud/`与`web/`的项目、客户选片版本和交付权限，运行方式见各模块README
4. 对照[真机验收清单](HARDWARE_ACCEPTANCE.md)，使用A7C II验证无线、蓝牙、格式兼容和性能

## 本地运行

Android构建与连接说明见[Android README](../android/README.md)。`scripts/bootstrap.py`目前面向macOS Apple Silicon准备工具；其他平台需自行安装Go、Android SDK、NDK和JDK17，并按构建脚本配置路径

云端与网页分别参考[cloud README](../cloud/README.md)和[web README](../web/README.md)。本机预览可依次执行：

```sh
python3 scripts/check.py cloud-package
python3 scripts/dev.py cloud
```

另开终端执行：

```sh
python3 scripts/dev.py web
```

打开`http://localhost:5177`，本机测试邀请码为`local-dev-only`。此配置仅供本地开发

## 验证与提交边界

实现阶段的本地验证记录见实施报告。A7C II实体机、蓝牙和真实性能尚未完成验收；模拟相机通过不等于真实无线兼容

安装包、生成的AAR、测试日志、工具链、照片、数据库、账号凭证和本地配置均不提交Git。克隆后需自行构建；文档中`artifacts/`和`build-logs/`指本地生成目录

本分支供协作审阅，未合并上游默认分支，也未部署云服务或发布正式安装包
