# iPhone试装

FrameLink最低需要iOS17。提供两种不同产物：

0.1.16目前仅源码候选，没有新IPA；二进制框架更新和App链接等待开发环境恢复。以下0.1.10设备包不包含新分页、Canon CCAPI、现代创意外观或富士PC AutoSave接收功能。

- `FrameLink-0.1.10-ios-unsigned.ipa`：本轮iPhone设备候选包，供重新签名；不能直接点开安装。0.1.7、0.1.8、0.1.9历史包保留
- `FrameLink-0.1.6-ios-simulator.zip`：保留的历史模拟器包，只供Mac上Xcode模拟器使用，不能装到iPhone；不包含0.1.7改动

## 普通Apple账户：自签试用

不需要上架App Store。可以用[AltStore Classic](https://faq.altstore.io/altstore-classic/your-altstore)为IPA签名并安装；免费账户的签名通常7天到期，需要定期刷新。

1. 朋友在自己的Mac或Windows电脑上，按[AltStore官方指南](https://altstore.io/)安装AltServer与AltStore Classic，并用自己的Apple账户完成配置
2. 将IPA存到iPhone的“文件”，在AltStore的“My Apps”里导入，按工具提示签名安装
3. 按iOS提示信任开发者、启用开发者模式。打开光屿时允许局域网访问，再连接相机
4. 按AltStore要求保持电脑可达并刷新签名；免费账户有应用数量及App ID限制，以工具提示为准

这里使用Classic的IPA自签方式。仅有IPA文件不代表已经签名，也不代表已经在朋友的手机上验证过。可选的[SideStore](https://docs.sidestore.io/docs/faq.html)同样需要首次配置与定期刷新；FrameLink尚未做这两种工具的实体安装兼容验收。

## 已有开发者计划会员

可使用Ad Hoc：登记朋友的设备，配置包含该设备的描述文件后导出签名IPA；或者通过TestFlight发测试邀请，两者都不要求正式上架App Store。参照[Apple分发说明](https://developer.apple.com/documentation/xcode/distributing-your-app-for-beta-testing-and-releases)。

## 本机构建

```sh
sh scripts/build-ios.sh
python3 scripts/check.py ios-device
python3 scripts/package-ios.py
```

设备包默认无签名；脚本检查arm64及iPhoneOS平台，排除模拟器产物，输出IPA及SHA256清单。若使用Xcode直接装到自己的手机，在Signing & Capabilities中选择自己的Team与设备后运行；本地签名配置和证书不提交到Git。
