# 协作审阅指南

本分支为光屿Android 0.1.8／iOS 0.1.6开发测试版，基于Aenlly-FTP的`7bef1146052b3c9f343856bbf3952ee7236b14d1`保留上游历史。Android采用独立包名`app.framelink.camera`，iOS工程位于`ios/`；原始项目源码和许可证保存在`third_party/aenlly-ftp/`

## 获取分支

```sh
git clone --branch feature/framelink-v1 https://github.com/Aenlly/Aenlly-FTP-FrameLink.git
cd Aenlly-FTP-FrameLink
```

## 建议审阅顺序

1. 阅读[实施与验收报告](IMPLEMENTATION.md)，区分已实现、本地已验证和真机待验收范围
2. 查看`android/`与`ios/`的四入口界面、本地数据与任务恢复，以及`camera-bridge/`的相机协议封装
3. 查看`cloud/`与`web/`的项目、客户选片版本和交付权限，运行方式见各模块README
4. 对照[真机验收清单](HARDWARE_ACCEPTANCE.md)，补充各型号的无线、FTP、格式和性能记录

## 本地运行

Android构建与连接说明见[Android README](../android/README.md)。`scripts/bootstrap.py`目前面向macOS Apple Silicon准备工具；其他平台需自行安装Go、Android SDK、NDK和JDK17，并按构建脚本配置路径

iOS需要完整Xcode、iOS SDK与Go桥，见[iOS README](../ios/README.md)。先构建XCFramework，再运行共享的FrameLink scheme；不要提交个人签名和DerivedData

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

实现阶段的本地验证记录见实施报告。A7C II已有部分无线实测记录，蓝牙、长时间稳定性和其他机型仍分项验收；模拟相机通过不等于真实无线兼容

安装包、生成的AAR、测试日志、工具链、验收样片原文件、数据库、账号凭证和本地配置均不提交Git。README使用的授权样片运行截图单独放在`docs/images/`。克隆后需自行构建；文档中`artifacts/`和`build-logs/`指本地生成目录

当前默认开发分支为feature/framelink-v1。此版本供协作与测试，尚未部署云服务或发布正式安装包
