# 上游版本与署名

| 来源 | 固定版本 | 许可 | 使用范围 |
|---|---|---|---|
| Aenlly/Aenlly-FTP | 7bef1146052b3c9f343856bbf3952ee7236b14d1 | GPL-3.0 | FTP接收架构，原始源码保留于third_party/aenlly-ftp |
| Knallli/alpha2go | 02883425c9445c653b2950210187cdd1547320c2 | Apache-2.0 | PTP/IP控制、卡内访问、流式下载与取景，源码位于third_party/alpha2go |
| Staacks/alpharemote | 4a950a86b650c68baa6419bbdaccaa9384020604 | GPL-3.0 | 蓝牙命令及状态行为参考，未宣称跨机型验证 |
| XiaoQAQ10086/SonnyLink | 183389124d772e9aaeff214257002034f1d37251 | MIT | 能力分层及渲染设计参考，未复用其旧款ScalarWebAPI作为A7C II协议 |
| [Apache Commons Net](https://commons.apache.org/proper/commons-net/) | 3.13.0 | Apache-2.0 | FTP/显式FTPS客户端，作为Gradle依赖使用 |
| [Chris Banes / Haze](https://github.com/chrisbanes/haze/tree/1.5.3) | 1.5.3 | Apache-2.0 | Compose背景图层捕获和旧系统玻璃降级，作为Gradle依赖使用 |
| petabyt/libfuji | 3328efc51f74d754f8fa76af70c6becdde62271f；另核对接收器fork22ad5a7dd5dd50b367da1002328ee12e26689cd4 | MIT | PC AutoSave发现、会话与媒体行为参考；未复制或链接C库；许可见docs/protocol-sources/libfuji-LICENSE.txt |
| petabyt/libpict | f1c94c68a1fa0671feccf371001b74a7275b3bd7；另核对接收器fork01d7352b399a403a49e36a90ba76c6a08e3d9227 | Apache-2.0 | TCP上的PTP容器、长度和释放路径参考；未链接C库；许可见docs/protocol-sources/libpict-LICENSE.txt |
| mikefsq/ptp | 0fe3c68516f8 | MIT | 现代Creative Look候选PTP编号与枚举参考；未移植其未经过机身验证的Sony远控实现，许可见docs/protocol-sources/mikefsq-ptp-LICENSE.txt |

上游作者的真机测试不等于本应用已通过对应设备验收。Android桥接代码和产品界面属于本工程；没有捆绑Sony专有SDK或其示例源码

Haze版权归Christopher Banes与Haze项目贡献者所有。Apache Commons Net版权归Apache Software Foundation所有。圆角玻璃折射着色器由本工程实现；界面仅参考Apple Liquid Glass的材质思路，不使用Apple专有代码或资源，也不声称属于Apple官方组件

alpha2go本地兼容改动：`SSHConfig`增加可选`DialContext`，供Android仅将相机socket绑定到局域网，保持云端网络独立；默认行为保持上游实现

alpha2go测试工具兼容改动：`ptpiptest.Options`增加JPEG预览、QA设备身份和可选控制能力列表，测试连接可主动断开；模拟器明确声明所模拟的自动对焦与快门能力。未配置时保持原测试默认行为，仅供独立协议验收，不包含在App安装包中

alpha2go夹具回放改动：测试服务增加可选`AllPropsPayload`，原样返回有来源的0x9209属性载荷，验证本App的Session边界；不将属性载荷回放称为完整握手、认证或取景实机测试。原二进制夹具及Apache-2.0署名保持不变

## 文档中的摄影作品

`docs/images/`中含有摄影作品的运行截图使用授权样片。照片版权与代码的GPL许可分别适用，照片继续按[Unsplash License](https://unsplash.com/license)使用；作者、来源、变换方式和核验记录见[VISUAL_QA.md](docs/VISUAL_QA.md)。样片原文件不随App或源码归档预装
