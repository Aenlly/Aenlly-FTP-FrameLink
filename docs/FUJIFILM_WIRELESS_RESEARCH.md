# 富士无线通道与待采数据

USB读卡、手机无线传图和PC AutoSave是三条不同的路线。当前Android源码新增独立手机无线入口、蓝牙配对与Wi-Fi交接，以及Go手机媒体后端；USB和默认关闭的AutoSave实验后端保留。公开实现、协议模拟、安装验证与机身实测分别记录。

## 本轮手机无线实现

- `FujiPhoneLink.kt`从机身已公布的配对服务区分legacy/secure路径，经系统蓝牙确认和专用Wi-Fi请求交接；仅为相机socket绑定网络，不切换云访问的默认路由
- `FujiGattProtocol.java`限定配对和Wi-Fi启动写入，客户端名为FrameLink；不写快门、时钟、GPS、备份或设置恢复特征。热点密码仅内存使用；token只在用户保存连接后进入本机加密仓库
- `protocol=fuji-phone`走`fuji_phone.go`，不需要AutoSave发现所得DSCNAME，也不能接受AutoSave机身状态；按实际公布的手机版本字段选择图库流程，不根据“固件2.06”字符串猜测模式
- `mediaScope=FUJI_PHONE_EXPOSED_MEDIA`只表示机身向手机开放的范围，UI标明“机身共享范围”。RAF仅在该范围实际返回时出现，不用AutoSave代取或假装全卡支持
- 独立手机端点覆盖两类版本协商、JPEG/RAF实际公布差异、安全缩略图、原字节、半包不发布、无序列号时会话隔离及空图库。Android另经真实Go/JNI、Room、2GB临时缓存与导入队列验证；不是机身抓包或真机通过
- 蓝牙与系统Wi-Fi交接已进入可构建代码，仍需富士机身实测；iOS没有接入此手机交接入口。本轮不开放富士取景、参数、对焦、快门或胶片模拟写入

机身操作参考[官方手机连接说明](https://fujifilm-dsc.com/en/manual/x-t30-2/connections/wireless/index.html)：拍摄信息界面按DISP/BACK，选择Bluetooth→PAIRING；系统/机身确认不能由“读到IP”替代。以下AutoSave研究与API继续单独适用。

### 0.1.29发现规则修正

真机0.1.28扫描有系统回调但未列出机身。源码确认旧逻辑在解析厂商数据前先检查名称与GATT服务，会漏掉部分无名称广播；这不是对真实机身广播内容的推测性定论。0.1.29改为生产使用的Java规则，厂商字段先验证，再处理可选名称和广告服务；实时去重结果，采用有界低延迟扫描，错误在连接页内呈现。

协议来源锁定[gkoh/furble 0cac22a的Fujifilm.h](https://github.com/gkoh/furble/blob/0cac22aacc3d9ef250be40849118af78a5f39f27/lib/furble/Fujifilm.h)及同版本的`FujifilmBasic.cpp`、`FujifilmSecure.cpp`：companyID为`0x04d8`；Android将两字节companyID与载荷分离，basic的type2加四字节token共五字节；secure的六字节载荷不作为basic token。广告服务与GATT配对服务分别处理。仅参考这些协议字段，未引入遥控、GPS、时钟同步或其日志输出代码。

用例覆盖无名称且无service的合法结构、其他厂商同形数据、短包/非法长度、secure载荷、已配对名称缓存、重复回调、名称后到、关闭/撤权后的晚回调和调用方修改缓冲区。测试数据按已核来源合成，不称作机身抓包。真实机身匹配结构只在本机记录长度/type等脱敏信息；系统回调数量不等于设备数量，XApp识别成功也不等于光屿识别成功。

## 已找到的实现

| 来源 | 固定版本 | 可参考范围 |
|---|---|---|
| petabyt/libfuji | 3328efc51f74d754f8fa76af70c6becdde62271f，MIT | 无线握手、PC AutoSave发现与注册、媒体读取、机身模式区分 |
| petabyt/libpict | f1c94c68a1fa0671feccf371001b74a7275b3bd7，Apache-2.0 | 底层容器和传输；不能因上层使用MIT就忽略此依赖许可证 |
| nyaadesu/fuji-autosave-to-immich | 195f7987a9379c10eb40c8d946ce2bdfd18eea5d | 作者报告X-T30 II、FW2.06通过PC AutoSave接收RAW＋JPEG；这是外部实测记录 |

本轮参考这些公开实现独立编写Go传输和接收器，未复制或链接C库，也未复制第三方接收器、Immich集成或同步／删除hook。MIT与Apache-2.0声明分别保存在[libfuji许可](protocol-sources/libfuji-LICENSE.txt)及[libpict许可](protocol-sources/libpict-LICENSE.txt)。

外部接收器固定版本实际引用libfuji fork `22ad5a7dd5dd50b367da1002328ee12e26689cd4`和libpict fork `01d7352b399a403a49e36a90ba76c6a08e3d9227`。已核对其逐行解析带空格／引号的型号、限制字符串写入、关闭发现连接、连接超时关闭socket、释放运行时／属性链表的路径。本项目不引入这些C内存分配路径；对发现头、容器和数组分别限长，使用完整读取处理TCP分片，关闭接收器时关闭全部监听与活动连接。

## 实验API与范围

- `NewFujiAutoSaveReceiver()`默认不打开端口；`Start(localIPv4, importerName, true)`才开启接收。接收器实例关闭后需新建实例再启动
- 注册与导入发现分开处理。仅在接受IMPORT回调后，`NextInvitation()`返回供`Session.Connect()`使用的连接配置；REGISTER不会返回媒体邀请
- `Session.Connect()`仅在`protocol=fuji-autosave`、`experimentalFuji=true`且带发现所得DSCNAME时开放该路线；默认配置会在关闭已有连接之前拒绝实验协议
- 仅开放设备信息、待传对象索引和指定JPEG／RAF原字节下载；`mediaScope=AUTOSAVE_PENDING_BATCH`，不表示整卡浏览。普通UI没有新增遥控或取景能力
- 会话只允许已知AutoSave客户端模式、协议版本和正确文件大小协商；不写D228，不删除或修改卡内媒体。标准DeviceInfo不支持时使用初始化名称与发现所得DSCNAME识别设备，固件留空
- 未核实安全缩略图之前，`canPreview=false`；浏览或生成预览不会偷偷下载整张RAF。导入后可从本地JPEG生成预览
- 以1MiB为上限分块，核对前后对象元数据、实际长度及本地SHA256；取消／半包断线后结束会话，不复用失去同步的socket；不发布半成品，不覆盖已有原片，也不开放断点续传
- 无时区的PTP拍摄时间暂按外部X-T30 II记录采用UTC解释，保留原始日期字符串用于身份核对；其他固件时区与EXIF关系仍需真机确认
- Android实验连接不会替换用户保存的Sony配置；普通UI和自动重连不会悄悄启用这个实验协议

生产代码位于`camera-bridge/bridge/fuji_discovery.go`、`fuji_wire.go`及`fuji_camera.go`。Go测试端点按公开布局构造响应，另以独立Kotlin TCP端点检查JNI和导入业务；两者均为合成数据，不是X-T30 II抓包，也不等于已运行外部vcam或富士机身。

## 不能套用通用PTP/IP的地方

- PC AutoSave先在局域网发现和注册：UDP／TCP51542用于注册，51541用于导入发现与回调，机身51540接收NOTIFY，随后连接55740命令口。上游文档示例是X-H1，不能当作X-T30 II抓包
- 数据通道使用Fuji初始化包及TCP上的USB风格PTP容器，与现有Sony PTP/IP信封不同
- 会话需分别读取机身／客户端状态及AutoSave协议版本。上游对AutoSave设置正确文件大小属性后才读取原片长度；不能跳过协商直接复用USB对象接口
- 上游无线对象句柄由机身对象计数生成，不能假定标准GetObjectHandles可用；AutoSave可能只公布本次待传照片，不能直接命名为“相机全部照片”
- 上游默认禁用AutoSave缩略图读取；直接套用普通GetThumb可能阻塞。预览需独立核实，不能为显示缩略图默认导入整张RAF
- 原片按最多1MiB的分段读取，需核对长度并处理取消、断线和未完成文件。已下载文件不能仅凭同名判定为同一资源

这些边界由实验后端分别处理；打开55740端口仍不足以宣布富士机身兼容。PC AutoSave传图不等于手机遥控、实时取景或胶片模拟控制。

## 最小采集与接入顺序

有对应机身时记录机型、固件、手机系统、App提交及连接模式；先采媒体读取，不拍照、不删除、不格式化、不恢复整机设置。

1. 分别记录“手机无线连接”和“PC AutoSave”的启动、配对、发现、注册及再次连接过程，保存脱敏后的请求／响应；明确哪些步骤要求用户在机身操作
2. 保存初始化响应、GetDeviceInfo、会话状态、协议版本、对象数量，以及协商前后的原片大小属性；不把X-H1示例补写成X-T30 II数据
3. 用已知JPEG＋RAF样本核对对象范围、句柄、文件名、长度、拍摄时间与文件时间。分别验证首次导入、再次导入和新增照片后索引变化，确定是否只是待传队列
4. 先核实安全的缩略图／预览读取方式，再读指定原片；记录原始长度、SHA256和传输耗时，预览副本不得替代RAF
5. 验证分块取消、拔网、相机休眠及重连后资源身份，保留已有原片，丢弃未完成临时文件；未验证之前不开放断点续传
6. 将脱敏载荷固定为回放夹具，通过独立Fuji后端接入现有媒体、图库、导入任务和选片记录；硬件与协议回放分别记录结果

当前仍缺少本项目X-T30 II无线机身会话、预览读取证据及真实网络下的资源身份／恢复记录。这不阻塞上述有来源的软件实现，但软件模拟与USB测试均不能填补机身验收缺口；机身不在身边时不反复请求借机，也不把未知操作码写成可用能力。

## 来源

- [libfuji无线注册与连接](https://github.com/petabyt/libfuji/blob/3328efc51f74d754f8fa76af70c6becdde62271f/docs/autosave.md)
- [libfuji模式与开发说明](https://github.com/petabyt/libfuji/blob/3328efc51f74d754f8fa76af70c6becdde62271f/docs/dev.md)
- [libfuji会话实现](https://github.com/petabyt/libfuji/blob/3328efc51f74d754f8fa76af70c6becdde62271f/lib/fuji.c)
- [libpict传输实现](https://github.com/petabyt/libpict/blob/f1c94c68a1fa0671feccf371001b74a7275b3bd7/src/transport.c)
- [X-T30 II外部AutoSave记录](https://github.com/nyaadesu/fuji-autosave-to-immich/tree/195f7987a9379c10eb40c8d946ce2bdfd18eea5d)
