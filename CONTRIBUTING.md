# 参与开发

感谢你愿意帮助Aenlly-FTP继续成长。FrameLink将FTP工具扩展为完整的相机与照片工作流；功能、源码验证、模拟器验收和真机兼容分别记录

## 从哪里开始

- 使用反馈：提供App版本、手机和Android版本、操作步骤、预期和实际结果
- 相机实测：按[真机清单](docs/HARDWARE_ACCEPTANCE.md)记录机型、固件、网络方式和实际能力，不把其他软件的兼容表当作本项目实测
- 界面改进：先说明使用场景，保留已有入口、权限、任务状态和业务流程；附实际安装包截图
- 代码贡献：优先围绕一个明确问题修改。Android、协议桥、服务端与客户网页可分别构建，结构见README
- 文档贡献：修正连接步骤、解释失败原因、补充已验证的环境与复现方法

## 本地验证

首次构建按[Android说明](android/README.md)准备工具链并生成协议AAR，再根据修改范围执行：

```sh
python3 scripts/check.py android-tests-build
python3 scripts/check.py android-lint
python3 scripts/check.py bridge
python3 scripts/check.py cloud
python3 scripts/check.py web
```

无需因纯文档修改重复构建全部模块。相机和FTP修改应验证文件字节、异常恢复和任务状态；界面修改应同时检查小屏、大字体和系统导航区域

`scripts/run-emulator.py`启动本项目独立的FrameLinkQA设备。`scripts/instrument.py`运行本地核心验收；需要模拟相机时，先运行`python3 scripts/dev.py simulator`。`scripts/visual-qa.py`只在该设备上使用授权摄影样片验收，不连接真实用户设备

构建和测试日志保留在`build-logs/`，工具链、照片、数据库和安装包不提交。不要附相机密码、SSH认证信息、云会话、真实客户照片或未授权素材

## 提交改动

1. 基于维护者指定分支创建功能分支，先检查工作区，保留他人已有修改
2. 一次提交围绕同一个目的，避免无关重构和批量格式化第三方代码
3. 建议采用Conventional Commits，摘要描述实际行为，例如`fix(FTP): 修复断网后任务状态未更新`；不要求所有贡献者使用同一种自然语言
4. PR说明改了什么、如何验证、没有验证什么。相机适配必须注明真实机型和固件；界面附截图和检查规格
5. 新增依赖或复用源码时更新[第三方来源](THIRD_PARTY_NOTICES.md)，保留上游许可证与署名

## 反馈入口

通过[GitHub Issues](https://github.com/Aenlly/Aenlly-FTP/issues)反馈或讨论具体问题。本分支提供问题、机型实测和功能建议表单模板；GitHub在模板合入默认分支后才会将其用于新建Issue

维护者确认合并、应用签名、安装包发布和云端部署是独立动作，提交PR不会自动执行这些操作
