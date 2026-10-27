# 上游版本与署名

| 来源 | 固定版本 | 许可 | 使用范围 |
|---|---|---|---|
| Aenlly/Aenlly-FTP | 7bef1146052b3c9f343856bbf3952ee7236b14d1 | GPL-3.0 | FTP接收架构，原始源码保留于third_party/aenlly-ftp |
| Knallli/alpha2go | 02883425c9445c653b2950210187cdd1547320c2 | Apache-2.0 | PTP/IP控制、卡内访问、流式下载与取景，源码位于third_party/alpha2go |
| Staacks/alpharemote | 4a950a86b650c68baa6419bbdaccaa9384020604 | GPL-3.0 | 蓝牙命令及状态行为参考，未宣称跨机型验证 |
| XiaoQAQ10086/SonnyLink | 183389124d772e9aaeff214257002034f1d37251 | MIT | 能力分层及渲染设计参考，未复用其旧款ScalarWebAPI作为A7C II协议 |
| [Apache Commons Net](https://commons.apache.org/proper/commons-net/) | 3.13.0 | Apache-2.0 | FTP/显式FTPS客户端，作为Gradle依赖使用 |
| [Chris Banes / Haze](https://github.com/chrisbanes/haze/tree/1.5.3) | 1.5.3 | Apache-2.0 | Compose背景图层捕获和旧系统玻璃降级，作为Gradle依赖使用 |

上游作者的真机测试不等于本应用已通过对应设备验收。Android桥接代码和产品界面属于本工程；没有捆绑Sony专有SDK或其示例源码

Haze版权归Christopher Banes与Haze项目贡献者所有。Apache Commons Net版权归Apache Software Foundation所有。圆角玻璃折射着色器由本工程实现；界面仅参考Apple Liquid Glass的材质思路，不使用Apple专有代码或资源，也不声称属于Apple官方组件

alpha2go本地兼容改动：`SSHConfig`增加可选`DialContext`，供Android仅将相机socket绑定到局域网，保持云端网络独立；默认行为保持上游实现

alpha2go测试工具兼容改动：`ptpiptest.Options`增加可选JPEG预览数据，仅供独立模拟协议验收使用；默认测试响应保持上游实现，模拟器程序不包含在Android安装包中
