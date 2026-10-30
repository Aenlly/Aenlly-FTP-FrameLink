# FrameLink iOS

SwiftUI原生应用，最低iOS17，适配iPhone与iPad。四个入口为相机、图库、项目和传输；iOS26采用系统Liquid Glass，旧系统使用Material浮层。

竖屏采用顶部快捷操作、快门区与专业面板，横屏使用完整窗口并默认收起菜单，单击画面才显示玻璃浮动控件。技术型号与实收尺寸在连接详情查看。

## 当前范围

- 索尼PTP/IP＋SSH连接、首次指纹确认、可选Keychain多设备账号保存，卡内分页、预览、原片下载、取景与中文参数
- 本地文件导入、网格／列表与大图共享选择，小中大缩略图、独立格式与导入状态、长按拖选、收藏、命名批次和图文单
- 客户独立选择、留言、姓名／手机号记录、改名、完成重开和删除记录
- FTP接收、FTP／显式FTPS客户端、目录、上传下载、新建文件夹和持久化任务
- 新连接默认不保存账号密码；已保存连接可切换、更新和忘记。传输记录可单条删除或清除已结束项，保留文件、文件入口和待恢复任务
- 仅移出列表与删除App内副本；不操作相机卡删除

蓝牙快门、云端账号与交付、双图比较和连拍合组尚未接入iOS。App界面目前为中文。

## 构建与运行

准备完整Xcode、iOS SDK和Go工具链。`scripts/bootstrap.py`可准备固定的Go与Android命令行工具，iOS构建不需要安装Android平台SDK或NDK；构建脚本会补齐固定版本的gomobile。

```sh
# 仓库根目录
sh scripts/build-ios.sh
open ios/FrameLink.xcodeproj
```

脚本生成`ios/Frameworks/Camera.xcframework`并构建iOS Simulator App，完成后打印App路径。相机协议与FTP直接调用Go桥，原片以文件路径传递，不把整张RAW搬过Swift桥。

模拟器使用本机临时签名，以便Keychain正常保存连接信息，不需要开发者账号。相机框架为静态库，只链接、不重复嵌入。实体iPhone仍需配置自己的开发团队与签名。

安装到iPhone：可在Xcode选择自己的开发团队和设备后运行，或生成IPA后使用自签工具安装，无需上架App Store。[朋友试装与IPA构建](INSTALL.md)。相机网络权限在首次访问局域网时由系统询问。

## 检查

```sh
swift test --package-path ios
swift run --package-path ios FrameLinkCoreCheck
# 先在另一终端运行本机模拟相机，参照camera-bridge/README.md
python3 scripts/check-ios-ui.py --device <专用模拟器UDID>
```

`FrameLinkCore`保存原子JSON索引、相对文件路径、原片、选择记录与任务。运行中的任务在重启后变为“已中断”，由用户重试。核心检查程序可以在仅有Swift命令行工具时执行；UI与协议桥编译需要完整Xcode。

## 运行边界

iOS后台会挂起局域网服务。切到后台会停止取景和FTP接收，当前任务中断或取消，返回后手动重开；页面不会继续显示服务在线。接收目录固定为App管理目录，不提供远程删除和原片读取。

FTP客户端使用被动模式与二进制传输；显式FTPS验证证书、主机名和TLS1.2以上。兼容MLSD及常见UNIX／DOS LIST，单目录超过一万项会提示进入子目录。上传先保存临时文件，再检查同名并改名；标准FTP无法保证跨客户端的原子“禁止覆盖”，并发写入仍需服务器侧权限策略。

受信任的相机指纹保留在Keychain；账号密码只有勾选保存才持久化，未保存时仅用于当前会话。库中只保存本地相对路径。生成的图文单不复制原片EXIF定位信息。测试截图与照片不上传到Git。

[本轮验证与未验收项](../docs/IMPLEMENTATION.md) · [相机选片与取景](../docs/SELECTION_AND_PREVIEW.md)
