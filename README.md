# Aenlly-FTP · FrameLink
<p align="center"><img src="design/brand/framelink-icon.png" alt="FrameLink 光屿" width="88"></p>
<p align="center"><strong>中文</strong> · <a href="README.en.md">English</a> · <a href="README.ja.md">日本語</a> · <a href="README.ko.md">한국어</a></p>

连接相机、看图选片、按需导入。FrameLink是从Aenlly-FTP继续开发的开源相机助手，保留FTP收发，也提供无线取景、客户现场选片和可选的云端交付。

**Android 0.1.25／iOS 0.1.9 开发预览** · Android 17适配（最低8.0） · iOS 17+工程 · GPL-3.0

## 看看界面
| 相机 | 图库 | 大图 |
|---|---|---|
| ![相机](docs/images/home.png) | ![图库](docs/images/gallery-0.1.22.png) | ![大图](docs/images/viewer.png) |

截图来自Android模拟器，使用已授权样片；[素材与视觉检查](docs/VISUAL_QA.md)。

![横屏取景（协议模拟画面）](docs/images/camera-v6/landscape-photo.png)

## 能做什么
- **相机**：记住连接，按机身能力提供取景、遥控和创意外观。Android手机取景控件原位转向，点按画面对焦；拍后自动导入可选，默认关闭。
- **图库**：RAW＋JPEG合并展示，按日期显示完整总数，后台补齐列表、按需读取缩略图；小／中／大网格和列表可切换，长按滑动多选，大图与网格共用选择。
- **看图与选片**：翻页、放大、收藏、比较，保存命名批次和图文选片单。“查看原片”临时缓存JPEG，不计入已导入；正式导入时优先复用缓存。缓存上限约2GB，可手动清理，不影响已保存照片和选片记录。
- **照片信息**：读取照片EXIF里的ISO、快门、光圈、焦距、镜头、机型、时间和GPS位置。缺失字段明确标注，不用机身当前设置或手机定位补值。
- **客户选片**：用姓名、手机号或场次记录，独立保存客户选择和留言，可改名、删除、完成后继续修改。
- **FTP**：手机接收相机上传；客户端浏览、上传、下载和新建目录，支持FTP及显式FTPS。
- **连接与记录**：可选择加密保存多台设备的账号密码，随时切换或忘记。传输页可清除已完成、已取消记录，文件与待处理任务保留。
- **本地管理**：导入手机和读卡器文件，移出列表或删除App保存的副本，保留相机卡原片。
- **项目交付**：Android与网页可连接自行部署的云服务，供客户通过访问码选片和下载成片。

本地功能无需账号。原片保持原始字节，预览与压缩副本单独生成。iOS首版包含本地图库、客户记录、索尼无线与FTP；蓝牙和云端尚未接入。[平台功能与验收](docs/IMPLEMENTATION.md)

## 连接相机
**FTP接收不限定品牌**：相机需支持FTP上传。手机和相机加入同一Wi-Fi或手机热点，启动“FTP 接收”，把手机地址、端口、账号和密码填入机身。

- **Sony网络**：开启电脑遥控及所需认证，填写机身地址并确认指纹。功能依机型、固件和实际协商而定；已有A7C II真机记录，其他目标仍待实测。
- **佳能CCAPI（实验）**：启用机身CCAPI后填写HTTP地址、端口及可选认证。EOS850D是待测目标，当前通过独立服务与Android流程模拟，尚无对应机身结果。
- **USB读卡（实验）**：手机通过OTG读取PTP/MTP设备，浏览并导入原片；不提供遥控。X-T30 II是待验目标，未据此支持富士无线遥控。系统已挂载的存储卡可使用“本机导入”。

新增CCAPI和USB入口目前仅Android。[连接说明](android/README.md#连接相机) · [通道与验收表](docs/CAMERA_BACKENDS.md)

普通FTP用于可信局域网；显式FTPS验证证书。iOS接收需要保持App前台。传输失败可重试，续传能力依通道而定，见连接说明。

## 构建
开发预览尚未发布到Releases，历史Release是早期FTP版本。
```sh
git clone --branch feature/framelink-v1 https://github.com/Aenlly/Aenlly-FTP-FrameLink.git
cd Aenlly-FTP-FrameLink
# macOS Apple Silicon，预先安装JDK17
python3 scripts/bootstrap.py
sh scripts/build-android.sh
# 完整Xcode及iOS SDK准备好后
sh scripts/build-ios.sh
```

Android源码构建输出：`android/app/build/outputs/apk/debug/app-debug.apk`。iOS脚本构建模拟器App，iPhone可通过自签工具安装IPA，见[试装说明](ios/INSTALL.md)。[Android](android/README.md) · [iOS](ios/README.md) · [云服务](cloud/README.md) · [客户网页](web/README.md)

## 一起开发
欢迎报告机型兼容、传输问题和不好用的地方。请附手机系统、相机型号与固件、连接方式、复现步骤；不要上传密码或客户原片。

[提Issue](https://github.com/Aenlly/Aenlly-FTP-FrameLink/issues) · [贡献说明](CONTRIBUTING.md) · [协作与检查](docs/REVIEW.md) · [真机清单](docs/HARDWARE_ACCEPTANCE.md)

## 许可
[GPL-3.0](LICENSE)。保留Aenlly原始署名与历史，早期源码在`third_party/aenlly-ftp/`。感谢alpha2go、α-Remote、SonnyLink、Apache及Haze；[依赖版本、许可与使用方式](THIRD_PARTY_NOTICES.md)。
