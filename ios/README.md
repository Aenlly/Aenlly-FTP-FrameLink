# FrameLink iOS

SwiftUI原生应用，最低iOS17，适配iPhone与iPad。四个入口为相机、图库、项目和传输；iOS26采用系统Liquid Glass，旧系统使用Material浮层。

竖屏采用顶部快捷操作、快门区与专业面板，横屏使用完整窗口并默认收起菜单，单击画面才显示玻璃浮动控件。技术型号与实收尺寸在连接详情查看。

## 当前范围

- 索尼PTP/IP＋SSH连接、首次指纹确认、可选Keychain多设备账号保存，卡内分页、预览、原片下载、取景与中文参数
- 现代Sony创意外观源码：读取真实预设代码和中文说明，只开放机身当前已提供的已识别候选，写入后回读确认；未知／自定义及细调项不开放
- Canon CCAPI实验只读源码：机身HTTP认证、媒体范围总数、预览、JPEG／CR3原字节及现有缓存／导入流程；不开放Canon取景、参数设置、对焦或拍摄，不代表EOS850D实机已通过
- 富士PC AutoSave实验接收源码：独立入口、默认关闭，只接机身IMPORT邀请中的当前待传批次；不提供富士无线遥控或卡内全量浏览，广播授权与真机待验
- 当前范围总数与后台轻量索引共用取页流程，筛选未补齐时显示已匹配数量及统计进度；缩略图仍按可见范围读取，不批量下载原片
- 本地文件导入、网格／列表与大图共享选择，小中大缩略图、独立格式与导入状态、长按拖选、收藏、命名批次和图文单
- 图库与大图按实际内容窗口切换单双栏；双图对比、归一化缩放／平移及文件信息共享状态为源码候选，UIKit窗口与系统分屏尚待UI验收
- 大图“查看原片”仅缓存JPEG，不改变导入状态或下载任务数量；正式导入校验后复用。预览缓存上限约2GiB，设置中可手动清理，RAW不复用JPEG缓存
- 文件信息读取ISO、快门、光圈、焦距、镜头、机型、时间、像素尺寸与GPS；无记录不补值，不请求手机定位。地图仅在坐标及基准可识别时由用户主动打开
- 客户独立选择、留言、姓名／手机号记录、改名、完成重开和删除记录
- FTP接收、FTP／显式FTPS客户端、目录、上传下载、新建文件夹和持久化任务
- 新连接默认不保存账号密码；已保存连接可切换、更新和忘记。传输记录可单条删除或清除已结束项，保留文件、文件入口和待恢复任务
- 仅移出列表与删除App内副本；不操作相机卡删除

蓝牙快门、云端账号与交付和连拍合组尚未接入iOS。App界面目前为中文。

0.1.15接入富士接收生命周期及缺失序列号的会话身份隔离。仅有本机隔离协议、Core与源码类型检查，无新版二进制框架、App链接、IPA或真机结果。[范围与权限](../docs/acceptance/2026-10-31-ios-fuji-autosave-0.1.15.md)

0.1.16修正富士接收页退出与撤销启用后的待连接发布边界，成功进入图库保留会话。84项Core测试通过；没有新版IPA或UI运行结果。[专项记录](../docs/acceptance/2026-10-31-ios-fuji-page-boundary-0.1.16.md)

0.1.17接入图库窗口与双图共享状态，95项Core测试及真实ImageIO限额／串行测试通过。大图单幅长边最多4096、对比每幅2048；这是显示预览，不是区域瓦片或源文件1:1解码。实际UI、分屏、键盘与低内存设备仍待验。[软件范围](../docs/acceptance/2026-10-31-ios-gallery-workspace-0.1.17.md)

0.1.18修正详情超出筛选范围的回归，恢复明确原片来源单图8192上限；普通预览4096、对比2048。101项Core通过，5000像素合成原片实际保留5000像素。无新版IPA或UI运行结果。[修正记录](../docs/acceptance/2026-10-31-ios-gallery-filter-precision-0.1.18.md)

0.1.9的30项核心测试、App及UI测试工程编译已通过；界面执行受本机CoreSimulator版本限制，iPhone安装、JPEG清晰度与真实相机缓存复用仍待验。[本轮记录](../docs/acceptance/2026-10-31-ios-preview-exif-0.1.9.md)

0.1.10修正慢快门时长、取消后的导入发布，以及同一照片落盘后的参数刷新。缓存复制与原片下载共用发布检查，独立暂存文件在取消后清理，先成功的文件保留。40项核心测试通过，UI与iPhone交互仍待验。[修正记录](../docs/acceptance/2026-10-31-ios-import-publication-0.1.10.md)

模拟器阻断已定位为Xcode27.2系统组件未更新，官方安装流程正等待管理员认证。[环境恢复及专项验收](../docs/acceptance/2026-10-31-ios-simulator-environment.md)

0.1.11当前是源码候选：48项核心测试及基于真实Go生成头文件的Swift源码类型检查通过。随附旧框架不包含新版分页入口，需在环境恢复后运行构建脚本重新生成二进制框架，再验证App链接和UI；未提供0.1.11 IPA，现有设备包仍为0.1.10。[分页同步范围](../docs/acceptance/2026-10-31-ios-camera-index-0.1.11.md)

0.1.12源码候选分开自动取页、用户重试及刷新意图，避免已排队的近尾动作在错误发布后自动重试。49项核心测试与源码类型检查通过，仍无新IPA或UI运行结果。[修正记录](../docs/acceptance/2026-10-31-ios-index-intent-0.1.12.md)

0.1.13通过真实Swift编码配置与共享Go HTTP后端完成Canon软件验证，57项核心测试、专项HTTP服务及源码类型检查通过。尚未重生成iOS二进制框架、链接App或运行UI。[只读适配记录](../docs/acceptance/2026-10-31-ios-canon-readonly-0.1.13.md)

0.1.14接入现代0xD0FA、确认式回读及连接代次保护，并修正0x500D慢快门显示。67项核心、独立PTP/IP与Swift解析／编码验证通过，仍是无新IPA、无App链接与UI运行的源码候选。[本轮记录](../docs/acceptance/2026-10-31-ios-creative-look-0.1.14.md)

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
# 只运行凭据、参数刷新与缓存清理四项，不连接真实相机
python3 scripts/check-ios-ui.py --suite boundaries --device <FrameLink-QA专用UDID>
# 合成250张相机索引的独立UI用例
python3 scripts/check-ios-ui.py --suite index --device <FrameLink-QA专用UDID>
# 安装认证未完成时可先检查源码，不启动模拟器或安装器
python3 scripts/check-ios-source.py
```

`FrameLinkCore`保存原子JSON索引、相对文件路径、原片、选择记录与任务。运行中的任务在重启后变为“已中断”，由用户重试。核心检查程序可以在仅有Swift命令行工具时执行；UI与协议桥编译需要完整Xcode。

## 运行边界

iOS后台会挂起局域网服务。切到后台会停止取景和FTP接收，当前任务中断或取消，返回后手动重开；页面不会继续显示服务在线。接收目录固定为App管理目录，不提供远程删除和原片读取。

FTP客户端使用被动模式与二进制传输；显式FTPS验证证书、主机名和TLS1.2以上。兼容MLSD及常见UNIX／DOS LIST，单目录超过一万项会提示进入子目录。上传先保存临时文件，再检查同名并改名；标准FTP无法保证跨客户端的原子“禁止覆盖”，并发写入仍需服务器侧权限策略。

受信任的相机指纹保留在Keychain；账号密码只有勾选保存才持久化，未保存时仅用于当前会话。库中只保存本地相对路径。生成的图文单不复制原片EXIF定位信息。测试截图与照片不上传到Git。

[本轮验证与未验收项](../docs/IMPLEMENTATION.md) · [相机选片与取景](../docs/SELECTION_AND_PREVIEW.md)
