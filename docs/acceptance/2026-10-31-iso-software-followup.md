# ISO触控软件回归

本轮仅使用独立FrameLinkQA模拟器emulator-5580和本机合成PTP服务，未操作真实相机、手机或平板。ADB未识别到物理设备，旧的“可以测试两分钟”回复不作为本轮前台授权或设备在线证据。平板ISO问题仍待真机复现。

## 实际运行对象

- 已安装主App：0.1.25，SHA256 `6681200fc9dd7abdec2a7aa4c41e6357e9686c198f80de909efff8177a0daee7`，未替换
- 本轮诊断组件：SHA256 `b18f7471231cfa637784429c617f1ffa9e2894bd855174eb6889e0b2a74f87e6`，设备与构建输出摘要一致；不覆盖冻结诊断包
- 合成服务：`artifacts/framelink-qa-simulator-pagination`，SHA256 `9dc2e21dba57bd01d8445215c60e150895b8e29b207d537a7c2e898ffeac5cd1`，仅监听127.0.0.1:15740，通过ADB反向端口连接
- 服务启动参数：`-dir artifacts/fixtures -ssh -creative-look -preview artifacts/camera-ui-lake.jpg`；构建信息为Go1.26.10、revision d7c2799a91fa8065f83fa2b3f2ccef54541f9e0b、modified=true，因此不声称该二进制对应干净源码

## 结果和边界

新增`isoTouchOpensPickerAndReadsBackWithoutReplacingLiveView`：以真实触点点击ISO快捷入口，核对0/90/270度握持方向的弹层开关、同一取景View/会话保留，以及合成PTP服务的ISO修改与恢复回读。测试选择界面实际使用的Sony扩展ISO属性，不能用同名标准ISO属性代替。

- 1280×800dp模拟窗口：ISO专项1项通过；大图缓存、选择状态与旋转专项1项通过（旋转后800×1280dp）
- 411×914dp原模拟窗口：现有取景方向保留1项、大图旋转1项通过
- 新诊断组件最终构建退出码0；最初离线构建缺少Kotlin依赖，联网补齐后构建通过
- 首次ISO测试误用同名标准属性，导致回读断言超时；修正测试属性选择后重新构建并运行通过，不记为生产功能修复

日志位于`build-logs/20261031-user-tablet-iso-test-build-final.log`、`20261031-user-tablet-iso-1280dp-final.log`和`20261031-user-tablet-photo-rotation-1280dp-final.log`，对应退出码文件均为0。模拟器显示尺寸和密度已恢复。

这证明合成协议与模拟窗口下的触控链路通过，不证明小米平板真实ISO描述符、系统触控、实际机身回读或物理陀螺仪验收通过。当前主线继续富士PC AutoSave与iOS软件适配，不重复扩展Android整套回归。
