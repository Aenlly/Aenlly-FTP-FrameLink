# Sony创意外观接入

现代Creative Look与旧CreativeStyle、PictureEffect分开处理。本实现只精确注册现代候选PTP属性0xD0FA，不使用Camera Remote SDK内部属性编号0x01C5，不放开整个Image类厂商属性。

## 来源与实际观察

- [Sony A7C II说明](https://helpguide.sony.net/ilc/2360/v1/en/contents/0411B_creative_look.html)列出ST、PT、NT、VV、VV2、FL、IN、SH、BW、SE和六个自定义槽。图片配置文件非关闭或特定Log拍摄时，该功能可能不可调
- [mikefsq/ptp属性表](https://github.com/mikefsq/ptp/blob/0fe3c68516f8/sony/props_gen.go)和[枚举表](https://github.com/mikefsq/ptp/blob/0fe3c68516f8/sony/values_gen.go)用于核对PTP候选编号及十个基础预设。该项目[OPERATION说明](https://github.com/mikefsq/ptp/blob/0fe3c68516f8/sony/OPERATION.md)明确Sony远控尚缺机身验证，因此单靠这张表不能宣布本App兼容
- 本项目保存的α6700原始0x9209载荷有352项属性，包含0xD0FA至0xD103；当时主项Current=0、Enabled=0、无可写枚举。属性存在、Writable=true仍不等于当前可编辑
- 2026-10-30经小米17对A7C II／FW2.01只读采集，完整0x9209返回391项属性。0xD0FA为UInt16、枚举、Enabled=1、Writable=true，Current=6，按已核对映射为FL；机身广告18个候选
- 该机身标准DeviceInfo未列出0xD0FA，但完整Sony描述符明确返回它。先前十条业务属性快照受App白名单过滤，不能据此判断机身没有创意外观功能

采集日志为本机build-logs/physical-ui-20261030-131829.log，`OK (1 test)`。诊断没有调用快门或写入相机设置。脱敏后的主要描述符投影固定在camera-bridge/bridge/testdata/a7cii-creative-look-20261030.json，源JSON的SHA256为a69c77aa43e307041ef5e63aa14c6f3ebf79e6a9cedabb96a9e44f9fdb1bb380。它是解码描述符投影，不冒充原始二进制报文。

## 产品行为

取景右侧创意入口和机身参数菜单使用同一属性。进入时重新读取机身参数，显示真实当前预设；当前为FL就显示FL，不由演示选中态或手机端滤镜决定。

可确认的基础预设可按机身当前Settable显示和选择；保留18个原始候选的信息，但尚未确认的其余候选不提供盲选。常规选择器使用紧凑网格，大字减少列数。不可写模式仍可查看，读取失败可重读，错误信息不表述为机身没有该功能。

选项以代码＋Sony官方中文名双行展示：ST标准、PT人像、NT中性、VV鲜明、VV2鲜明2、FL胶片、IN哑光、SH柔和高调、BW黑白、SE棕褐色。取景浮动入口仍只显示当前代码；中文说明不改变协议值或机身候选范围。

设置走原有Session.SetProperty和CameraRuntime刷新路径。选中态以机身回读为准；未确认时不假定成功。断开会清空当前参数，重连重新读取。机身拒绝时刷新创意外观状态，不静默重发风格设置。

## 写入保护

保留Danger、Writable、Enabled、描述符类型／Form与最新Settable校验，并核对单项读取返回的属性编号。SDK内部编号、旧创意风格、未确认的细调／重置项、超范围编号和未知预设均拒绝写入。0xD0FB至0xD103当前仅纳入只读诊断，不自动关闭图片配置文件或修改其他拍摄设置。

只读诊断仅输出完整回复的属性代码列表和创意外观候选范围的数字；不输出Wi-Fi、会话令牌、作者等无关字符串。

## 验证边界

独立协议模拟已验证ST／FL读取、机身广告候选限制、设置后回读及重连；Android原生UI经真实JNI调用模拟端点完成选择和回读。模拟画面不会加手机端滤镜，不能用样片颜色代替机身验证。

A7C II的真实读取及App显示当前FL／候选界面已通过，界面日志为build-logs/physical-ui-20261030-134913.log，记录机身18候选、10个已识别基础预设、可写标志与FL选中态；该次未点击候选或触发快门。

后续在同一机身与小米17补充通过FL→ST及FL→BW实际设置、回读和恢复原FL。BW切换前后的相机原始JPEG预览分别有色彩／黑白，中文面板也在真机核对，见[0.1.21补充验收](acceptance/2026-10-30-xiaomi17-0.1.21.md)。未触发快门；不能据此宣称十种预设逐项完成色彩验收。其他机型的代码共用不等于完成机身兼容验收。对比度、高光、阴影、自定义槽及其他风格扩展另行验证。
