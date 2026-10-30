# iOS模拟器环境恢复记录

2026-10-31。本轮定位系统环境并准备专项UI验收，不改App业务、不重建已冻结iPhone IPA，也不操作实体设备、原片或其他会话的模拟器。

## 已确认

| 项目 | 当前证据 |
|---|---|
| macOS | 27.0.1／26A434 |
| Xcode | `/Applications/Xcode.app`，27.2／27B52 |
| 开发目录 | `DEVELOPER_DIR`未设置；`xcode-select -p`为`/Applications/Xcode.app/Contents/Developer`，没有修改全局选择 |
| simctl | `/Applications/Xcode.app/Contents/Developer/usr/bin/simctl` |
| 磁盘CoreSimulator | `/Library/Developer/PrivateFrameworks/CoreSimulator.framework`，1174.9.2，目录由root管理 |
| 当前Xcode要求 | 编译日志报告需要1174.21.0，现有框架过旧 |
| 已安装系统组件 | pkgutil记录XcodeSystemResources27.1；当前App已更新为27.2 |
| 许可 | 当前LicenseInfo的EA2002与已接受记录相同，不需要重复接受许可 |
| 首次启动检查 | `xcodebuild -checkFirstLaunchStatus`退出69，系统组件尚不完整 |
| 已有初始化记录 | 两份0.1.7初始化日志为空、退出143，是被中断的记录，不是组件已安装证明 |

本用户CoreSimulatorService进程57358以UID501运行，启动于10月28日；simdiskimaged及部分SimLaunchHost是root服务。检查时本用户12份device.plist均为Shutdown状态，没有观察到DeviceHub／Simulator前台进程；没有停止任何服务、其他用户进程或模拟器。因为磁盘框架本身仍旧，仅重启用户服务不能补齐系统组件。

## 当前阻断及用户操作

Xcode实际窗口显示“Install Required”，说明需要安装管理模拟器与设备的系统级组件。点击官方Install后显示“Waiting for other install tasks on the system”。现存xcodebuild安装进程27351的采样堆栈停在：

`DVTFirstLaunchPackageInstaller → PKInstallRequest authorizeForRight → AuthorizationCopyRights`

同时存在本用户SecurityAgent27352。安装需要用户在系统认证窗口完成管理员／Touch ID授权；不是要求再次接受许可，也不是继续下载整套SDK。没有读取、索要或输入密码。

`XcodeSystemResources.pkg`已通过pkgutil签名检查，来源为Apple Software，可信时间戳为2026-10-23。该操作更新共享开发组件，可能由官方安装器重新加载相关服务；不会清除现有模拟器数据。本轮未改全局代理、未切换toolchain、未卸载／重装SDK、未重启Mac，也未killall。

一次管理员操作请求已发出，等待本人完成。授权后先核对磁盘框架与服务版本、运行时和使用情况，再决定是否确需仅重启本用户旧服务；不以认证完成直接判定模拟器已恢复。

进程采样保留在`build-logs/ios-coresimulator-installer-wait.sample`。

## 专项验收准备

`scripts/check-ios-ui.py --suite boundaries --device <FrameLink-QA专用UDID>`仅选择：

- 相机A/B凭据切换、取消保存及编辑后的清理
- FTP A/B凭据切换、取消保存及编辑后的清理
- 文件EXIF读取及清理缓存后正式原片仍可读
- 信息页打开期间，同一照片由预览转为本机原片后的参数更新

环境恢复后新建本项目专用模拟器，只用合成数据、QA启动参数、独立QA Keychain服务和目录。运行器会先为模拟器构建临时签名App与测试组件，随后test-without-building；安装前后记录二者摘要、源码提交、实际Xcode／开发目录、运行时、设备和窗口尺寸，保存完整日志、截图及xcresult。四项全部通过、无跳过且安装身份匹配才判定专项通过。

当前仅完成Python静态检查与SDK-only Swift类型检查（退出0）。首次直接探针缺少XCTest Swift overlay搜索路径，补充平台Developer/usr/lib后通过，未改测试断言。日志为`build-logs/ios-ui-boundaries-typecheck-final.log`。这些结果不代表UI已运行；没有生成模拟器通过或实体通过结论。

## 保留的后续验收

管理员授权及模拟器恢复后执行上述专项；iPhone签名安装、真实相机与大图性能仍独立待验。平板横竖屏／分屏、更多机型与USB／CCAPI真实通道沿用原验收清单，不因本环境条件或tag权限丢弃。已冻结0.1.7／0.1.8／0.1.9／0.1.10及Android安装包保留。

官方流程参考：[安装Xcode附加组件](https://developer.apple.com/documentation/xcode/downloading-and-installing-additional-xcode-components)。
