# Aenlly-FTP · FrameLink
<p align="center"><img src="design/brand/framelink-icon.png" alt="FrameLink 光屿" width="88"></p>
<p align="center"><strong>中文</strong> · <a href="README.en.md">English</a> · <a href="README.ja.md">日本語</a> · <a href="README.ko.md">한국어</a></p>

连接相机、看图选片、按需导入。FrameLink是从Aenlly-FTP继续开发的开源相机助手，保留FTP收发，也提供无线取景、客户现场选片和可选的云端交付。

**Android 0.1.7／iOS 0.1.6 开发预览** · Android 17适配（最低8.0） · iOS 17+工程 · GPL-3.0

## 看看界面
| 相机 | 图库 | 大图 |
|---|---|---|
| ![相机](docs/images/home.png) | ![图库](docs/images/gallery.png) | ![大图](docs/images/viewer.png) |

截图来自Android模拟器，使用已授权样片；[素材与视觉检查](docs/VISUAL_QA.md)。

## 能做什么
- **相机**：记住连接信息，首次确认相机指纹；浏览卡内照片、下载原片、取景和遥控，按机身能力开放。
- **图库**：RAW＋JPEG合并展示，长按滑动多选，大图与网格共用选择；收藏、比较、保存命名批次和图文选片单。Android相机列表按拍摄时间倒序，滑到底自动加载。
- **客户选片**：用姓名、手机号或场次记录，独立保存客户选择和留言，可改名、删除、完成后继续修改。
- **FTP**：手机接收相机上传；客户端浏览、上传、下载和新建目录，支持FTP及显式FTPS。
- **本地管理**：导入手机和读卡器文件，移出列表或删除App保存的副本，保留相机卡原片。
- **项目交付**：Android与网页可连接自行部署的云服务，供客户通过访问码选片和下载成片。

本地功能无需账号。原片保持原始字节，预览与压缩副本单独生成。iOS首版包含本地图库、客户记录、索尼无线与FTP；蓝牙和云端尚未接入。[平台功能与验收](docs/IMPLEMENTATION.md)

## 连接相机
**FTP接收不限定品牌**：相机需支持FTP上传。手机和相机加入同一Wi-Fi或手机热点，启动“FTP 接收”，把手机地址、端口、账号和密码填入机身。

**无线浏览与遥控目前使用索尼适配器**：在相机开启电脑遥控及所需认证，同网连接，填写相机地址并确认指纹。不同机型、固件提供的功能可能不同；无法连接时可使用FTP或本地导入。A7C II已有部分真机记录，其他型号欢迎补充测试，不承诺所有索尼机型通用。[连接说明](android/README.md#连接相机)

普通FTP用于可信局域网；显式FTPS验证证书。iOS接收需要保持App前台。传输失败可重试，当前从头传输。

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

Android输出：`artifacts/FrameLink-0.1.7-debug.apk`。iOS脚本构建模拟器App，iPhone可通过自签工具安装IPA，见[试装说明](ios/INSTALL.md)。[Android](android/README.md) · [iOS](ios/README.md) · [云服务](cloud/README.md) · [客户网页](web/README.md)

## 一起开发
欢迎报告机型兼容、传输问题和不好用的地方。请附手机系统、相机型号与固件、连接方式、复现步骤；不要上传密码或客户原片。

[提Issue](https://github.com/Aenlly/Aenlly-FTP-FrameLink/issues) · [贡献说明](CONTRIBUTING.md) · [协作与检查](docs/REVIEW.md) · [真机清单](docs/HARDWARE_ACCEPTANCE.md)

## 许可
[GPL-3.0](LICENSE)。保留Aenlly原始署名与历史，早期源码在`third_party/aenlly-ftp/`。感谢alpha2go、α-Remote、SonnyLink、Apache及Haze；[依赖版本、许可与使用方式](THIRD_PARTY_NOTICES.md)。
