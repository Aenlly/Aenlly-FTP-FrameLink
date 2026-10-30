# iOS富士PC AutoSave软件接入

2026-10-31，0.1.15／build16源码候选。没有iOS二进制框架重建、App链接、新IPA、UI执行、iPhone或富士实机验收。本轮不启动真实LAN发现，不操作手机、平板、真实相机或既有用户照片。

## 实现范围

- 共享Go身份：保留非空稳定序列号的原身份算法。标准DeviceInfo不可用或序列号为空时使用安全随机会话命名空间，DSCNAME／型号不再冒充稳定设备身份。同一会话刷新保持身份，不同会话的资源ID、缓存和任务引用不互认；旧记录与已保存原字节保留
- iOS相机页有独立“富士PC AutoSave”实验入口，默认关闭，用户选择本机实际局域网IPv4与接收名后主动开始。普通连接、保存连接、重连及协议切换不启动接收器；富士配置不能写入普通连接历史或替换Sony最近连接
- 真实Go接收器经后台队列调用Start／NextInvitation／Close；每次重启创建新实例。REGISTER不是媒体邀请，只有IMPORT产生当前批次配置；收到邀请后先关闭监听，再由用户在30秒内确认连接共享Session，过期邀请不再接受
- 停止、离开接收页、后台、超时和错误关闭本实例与等待；停止时先失效代次，迟到邀请与迟到Start不恢复监听。关闭队列与阻塞等待分离，停止正在连接的富士Session也会失效连接代次
- 配置显式携带`protocol=fuji-autosave`、`experimentalFuji=true`和发现的`fujiDeviceName`；清除Sony／Canon用户名、密码、指纹与GUID。缺少同意、IMPORT范围或设备名时拒绝
- 仅当前`AUTOSAVE_PENDING_BATCH`对象。JPEG／RAF独立文件，`canPreview=false`强制关闭自动缩略图读取；只有主动单张JPEG临时预览或正式导入才读取对应原字节。复用既有取消安全发布、缓存、索引与状态模型，不声称机身队列可重读或断点续传已验证
- 机型和固件未知时保留未知状态；不提供富士取景、拍摄、参数控制、D228、卡内删除、Immich或云端钩子

AutoSave包含已有来源支持的发现、注册、客户端状态、版本和正确文件大小协商写入，**不是零写入协议**。本轮未加入曝光、快门、机身卡删除或设置恢复操作。[协议来源与边界](../FUJIFILM_WIRELESS_RESEARCH.md)

## 验证与证据

78项Core测试通过，新增11项覆盖默认关闭、旧配置解码、协议切换清凭据、实验同意、严格邀请、Sony历史保留、停止／迟到邀请、迟到Start、超时／错误、邀请过期、重启新实例、未知机型固件及无预览能力。另验证同名文件在新会话不继承旧缓存、导入标记、任务或选择，旧照片文件保留。缓存测试首次缺少自身临时media目录，修正测试准备后重跑通过，不记为App生产修复。

共享Go专项验证缺失DeviceInfo／空序列号的同名机型跨会话隔离、同会话刷新稳定、旧批次请求拒绝与已保存文件不变；稳定序列号的身份不变。现有JPEG／RAF原字节、取消／断线、文件变更与防覆盖测试一并运行。

跨语言隔离测试：UDP发现、REGISTER／IMPORT回调、NOTIFY和命令TCP全部使用127.0.0.1及动态端口。真实Go IMPORT配置交给Swift `FujiAutoSaveController.decodeInvitation`和实际连接编码器，再进入共享Session.Connect；连接信息和待传JPEG／RAF页面交回Swift解析。验证REGISTER不产生媒体配置，索引解析不导入文件或读取缩略图。没有调用默认绑定0.0.0.0的实验Start。它不是iOS原生框架、系统权限或真实机身端到端验证。

共享Go与跨语言专项共12个顶层测试、18个子场景通过，无SKIP；包含新富士链路及Sony／Canon连接编码回归。日志最终副本随`artifacts/FrameLink-0.1.15-ios-evidence/`归档，数量、退出码、源码版本与摘要以该目录`receipt.json`为准。源码类型检查基于当前Go真实生成头文件，不能代替旧二进制框架更新或App链接。

## iOS权限与仍缺的条件

Apple要求局域网访问提供`NSLocalNetworkUsageDescription`，广播／组播还需要`com.apple.developer.networking.multicast`授权及对应签名。本工程已有并补充了用途说明，但**没有添加或宣称获得受限组播授权**；当前签名描述文件、局域网许可弹窗及真实广播接收都未验收。此协议不是Bonjour，未虚构`NSBonjourServices`服务类型。[Apple TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)、[组播授权](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.networking.multicast)、[Apple网络API说明](https://developer.apple.com/documentation/technotes/tn3151-choosing-the-right-networking-api)

原Mac系统组件管理员条件保持待处理，不再次触发授权提示。现有iOS0.1.10未签名设备包与Android0.1.25冻结包不更新、不覆盖。共享Go身份改动影响未来Android后端源码构建，不因本片改动重建Android安装包，也不将已有Android模拟记录记为新机身验收。此前已启动的ISO诊断构建与模拟结果单独保留在[ISO软件补测](2026-10-31-iso-software-followup.md)。

富士目标型号／固件、JPEG／RAF真实队列、恢复行为、iOS局域网与广播签名，以及独立UI与iPhone安装仍待验。
