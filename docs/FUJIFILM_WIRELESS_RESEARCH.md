# 富士无线通道与待采数据

USB读卡、手机无线传图和PC AutoSave是三条不同的路线。当前App已实现USB读卡；尚未接入富士无线后端。下面记录可复用的协议资料与缺口，不将其他项目的成功记录作为本App兼容证明。

## 已找到的实现

| 来源 | 固定版本 | 可参考范围 |
|---|---|---|
| petabyt/libfuji | 3328efc51f74d754f8fa76af70c6becdde62271f，MIT | 无线握手、PC AutoSave发现与注册、媒体读取、机身模式区分 |
| petabyt/libpict | f1c94c68a1fa0671feccf371001b74a7275b3bd7，Apache-2.0 | 底层容器和传输；不能因上层使用MIT就忽略此依赖许可证 |
| nyaadesu/fuji-autosave-to-immich | 195f7987a9379c10eb40c8d946ce2bdfd18eea5d | 作者报告X-T30 II、FW2.06通过PC AutoSave接收RAW＋JPEG；这是外部实测记录 |

本轮未复制或链接这些C库。后续复用应同时保留各层许可证，隔离备份恢复、RAW转换和其他非媒体操作。

## 不能套用通用PTP/IP的地方

- PC AutoSave先在局域网发现和注册：UDP／TCP51542用于注册，51541用于导入发现与回调，机身51540接收NOTIFY，随后连接55740命令口。上游文档示例是X-H1，不能当作X-T30 II抓包
- 数据通道使用Fuji初始化包及TCP上的USB风格PTP容器，与现有Sony PTP/IP信封不同
- 会话需分别读取机身／客户端状态及AutoSave协议版本。上游对AutoSave设置正确文件大小属性后才读取原片长度；不能跳过协商直接复用USB对象接口
- 上游无线对象句柄由机身对象计数生成，不能假定标准GetObjectHandles可用；AutoSave可能只公布本次待传照片，不能直接命名为“相机全部照片”
- 上游默认禁用AutoSave缩略图读取；直接套用普通GetThumb可能阻塞。预览需独立核实，不能为显示缩略图默认导入整张RAF
- 原片按最多1MiB的分段读取，需核对长度并处理取消、断线和未完成文件。已下载文件不能仅凭同名判定为同一资源

因此，现有USB适配器和一个能打开55740端口的socket，都不足以宣布富士无线可用。PC AutoSave传图也不等于手机遥控、实时取景或胶片模拟控制。

## 最小采集与接入顺序

有对应机身时记录机型、固件、手机系统、App提交及连接模式；先采媒体读取，不拍照、不删除、不格式化、不恢复整机设置。

1. 分别记录“手机无线连接”和“PC AutoSave”的启动、配对、发现、注册及再次连接过程，保存脱敏后的请求／响应；明确哪些步骤要求用户在机身操作
2. 保存初始化响应、GetDeviceInfo、会话状态、协议版本、对象数量，以及协商前后的原片大小属性；不把X-H1示例补写成X-T30 II数据
3. 用已知JPEG＋RAF样本核对对象范围、句柄、文件名、长度、拍摄时间与文件时间。分别验证首次导入、再次导入和新增照片后索引变化，确定是否只是待传队列
4. 先核实安全的缩略图／预览读取方式，再读指定原片；记录原始长度、SHA256和传输耗时，预览副本不得替代RAF
5. 验证分块取消、拔网、相机休眠及重连后资源身份，保留已有原片，丢弃未完成临时文件；未验证之前不开放断点续传
6. 将脱敏载荷固定为回放夹具，通过独立Fuji后端接入现有媒体、图库、导入任务和选片记录；硬件与协议回放分别记录结果

当前缺少本项目X-T30 II无线会话载荷、预览读取证据和资源身份／恢复记录。USB业务链路测试不会填补这些缺口；机身不在身边时不反复请求借机，也不把未知操作码写成可用能力。

## 来源

- [libfuji无线注册与连接](https://github.com/petabyt/libfuji/blob/3328efc51f74d754f8fa76af70c6becdde62271f/docs/autosave.md)
- [libfuji模式与开发说明](https://github.com/petabyt/libfuji/blob/3328efc51f74d754f8fa76af70c6becdde62271f/docs/dev.md)
- [libfuji会话实现](https://github.com/petabyt/libfuji/blob/3328efc51f74d754f8fa76af70c6becdde62271f/lib/fuji.c)
- [libpict传输实现](https://github.com/petabyt/libpict/blob/f1c94c68a1fa0671feccf371001b74a7275b3bd7/src/transport.c)
- [X-T30 II外部AutoSave记录](https://github.com/nyaadesu/fuji-autosave-to-immich/tree/195f7987a9379c10eb40c8d946ce2bdfd18eea5d)
