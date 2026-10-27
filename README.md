# 光屿 · FrameLink

开源Android相机助手：连接、拍摄、选片、导入、客户选片与交付

当前为0.1.1开发测试版：包含FTP客户端与接收配置补全、Bento首页和玻璃导航；A7C II实体机、蓝牙与真实性能待验收，尚未正式发布

- [实施与验收报告](docs/IMPLEMENTATION.md)
- [协作审阅指南](docs/REVIEW.md)
- [FTP功能与新版界面](docs/FTP_AND_UI.md)
- [Android使用与连接](android/README.md)
- [真机验收清单](docs/HARDWARE_ACCEPTANCE.md)
- 本地生成的开发包、源码归档和校验清单见artifacts目录，该目录不提交Git

## 工程

- `android/`：Kotlin、Compose、本地照片与任务、FTP和蓝牙遥控
- `camera-bridge/`：alpha2go的Android桥接层，按文件流传输原片
- `cloud/`：Spring Boot项目、权限、选片版本与交付API
- `web/`：摄影师工作台与免安装客户选片网页
- `third_party/`：锁定的上游源码和原始许可证
- `docs/IMPLEMENTATION.md`：实施、验证与真机验收记录

本地连接和照片管理不要求云账号。云服务按需配置

## 构建与运行

先执行 `python3 scripts/bootstrap.py` 准备项目内的Go和Android编译工具。随后按各模块README构建。配置、日志、照片和签名文件均不提交

Android包名为`app.framelink.camera`，不会覆盖Aenlly-FTP。公开发布和相机兼容认证是独立验收步骤

本地预览：先运行`python3 scripts/check.py cloud-package`，再分别运行`python3 scripts/dev.py cloud`和`python3 scripts/dev.py web`，打开http://localhost:5177。开发启动器仅监听本机，邀请码为local-dev-only；正式环境必须另设邀请码、独立数据库、私有对象存储和HTTPS

回归入口为`scripts/check.py`，支持cloud、web、bridge、upstream、android-tests-build和android-lint。独立模拟器使用`scripts/run-emulator.py`，模拟相机使用`scripts/dev.py simulator`。`scripts/instrument.py`只向FrameLinkQA安装并执行测试，ADB反向端口不计作真实Wi-Fi验收

## 开源来源

本工程以GPL-3.0发布，保留Aenlly-FTP的原始许可证与作者署名。上游版本及各模块许可证见[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
