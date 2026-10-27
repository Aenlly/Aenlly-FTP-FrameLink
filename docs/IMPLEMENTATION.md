# 实施与验收报告

当前版本：光屿FrameLink 0.1.3开发测试版

**源码实现、安装包构建和本地软件验收已完成；完整V1的A7C II实体机与发布验收尚未完成。** 用户反馈过同Wi-Fi下15740端口连接被拒绝，本轮改进身份确认和连接引导；尚无真实机身连接成功结果。没有拍摄用户照片、删除卡内数据或公开部署服务

## 已开发能力

| 子系统 | 实现范围 |
|---|---|
| Android | 相机、图库、项目、传输，连接引导、日期和筛选、RAW/JPEG分组、对比、标记、原生取景、专业辅助、倒计时与拍后查看 |
| 协议 | 锁定alpha2go，Android AAR，SSH指纹自动读取、首次确认与变化提示、认证信息记忆、独立相机socket绑定、卡内分页、预览、分块下载、参数与取景 |
| FTP | 只接收、二进制原片、唯一文件、fsync、任务持久化后回复226，重启恢复已落盘文件；图库处理独立排队 |
| FTP客户端 | FTP/显式FTPS、加密账号、目录与搜索、上下级、多选下载、上传、新建文件夹、任务进度、取消和重试 |
| 接收配置 | 自定义端口、系统授权保存目录、独立压缩副本、JPEG质量和尺寸、格式范围、副本相册同步 |
| 界面 | 紧凑连接状态、两项主要操作与四项快捷工具、最近照片、密集图库、深浅场景主题、加载失败与重试、克制玻璃浮层 |
| 蓝牙 | 扫描、配对、订阅、半按与快门、连接状态、上次设备、通知栏入口 |
| 本地 | Room照片与任务、原片与相册URI分开、预览缓存、取消重试、仅移出图库或删除本机文件、Room1→2→3迁移 |
| 现场选片 | 无需登录，独立场次、客户选择留言、锁定和重开、CSV导出；与摄影师收藏和标记分开 |
| 云服务 | Java17/Spring Boot、邀请注册、会话摘要、隔离、配额、私有资源、预览采样、EXIF方向与定位移除 |
| 客户和交付 | 访问码、自动保存、设备草稿、冲突、提交版本、确认与重开、关联成片、下载授权、撤销 |
| 工作台同步 | 轻量版本检查；仅变更后读取详情，返回窗口和恢复联网时同步 |

本地连接、导入、标记和现场客户选片不依赖云账号。云上传仅处理主动选定的预览或成片，默认等待非计费网络

## 已验证

| 层级 | 结果 | 证据 |
|---|---|---|
| Android构建 | 通过；app.framelink.camera，min26/target36，arm64/x86_64 | build-logs/android-tests-build.log |
| Android JVM | 11项通过：照片分组与配置、路径校验、FTP流字节与摘要、异常和取消、指纹比较、可操作错误提示 | android/app/build/test-results/testDebugUnitTest/ |
| Android安装运行 | Android15/API35、arm64独立FrameLinkQA模拟器 | build-logs/android-instrument.log |
| Android验收 | 16项通过：原有9项，加指纹确认与记忆1项，照片移除、文件及相册副本删除、共享与目录保护、迁移、界面操作和缓存异常6项 | OK (16 tests) |
| FTP数据流对照 | 同一32MiB＋73字节文件，预热后每条路径7轮；输出write调用4097→129，字节与摘要均通过 | [条件与逐轮数据](FTP_PERFORMANCE.md)；build-logs/ftp-benchmark.log |
| 0.1.1小屏记录 | 360×640dp、字体120%，FTP连接下载到大图流程通过，四个主要入口首屏可见；本行是上一版记录 | build-logs/android-instrument-small-font.log；artifacts/android-home-small-large-font.png |
| 0.1.3摄影界面 | 3项通过：默认屏、小屏120%字体、手势导航；授权真实样片、六个入口、多选、完整比较、大图缩放像素、末张照片与系统区域 | build-logs/android-visual-phone.log、android-visual-small.log、android-visual-gesture.log |
| 0.1.3连接小屏 | 360×640dp、字体120%，密码显隐、首次确认、取消、记忆重连和指纹变化流程通过 | build-logs/android-identity-small.log |
| Go桥 | 5项通过，含5MiB RAW/JPEG字节一致性、不锁机身模式、读取公钥时零认证、握手取消与超时 | build-logs/bridge.log |
| 上游 | 5个包测试通过 | build-logs/upstream.log |
| 云服务 | 11项通过：并发版本、历史、链接失效、配额回滚、交付权限、上传前鉴权、方向与元数据 | cloud/target/surefire-reports/ |
| 网页 | 类型和生产构建通过，5项自动保存队列测试通过 | build-logs/web.log |
| 网页端到端 | 8个真实交互场景通过；1440×1000和390×844；上传、刷新保留、自动同步、成片字节、撤销 | artifacts/web-e2e-results.json |
| Android静态 | lintDebug通过，0错误；仍有依赖更新及第三方代码警告 | android/app/build/reports/lint-results-debug.xml |
| 原生库和包 | ELF LOAD段0x4000对齐，冻结时另验16KB ZIP对齐和证书 | artifacts/artifact-manifest.json |

功能测试使用本地合成图片和测试账号，视觉检查使用授权摄影样片，没有读取真实客户照片、联系人或机身密码。完整成功与失败日志保存在build-logs，失败日志按时间归档

0.1.3新增自动读取并确认相机指纹、加密连接记忆、密码显隐、固定连接按钮，以及图库和大图中的两种移除方式。照片模型增加本机隐藏状态，旧数据通过迁移保留；本轮重跑Go桥、Android构建、JVM、安装回归、三种规格视觉检查与lint。云端、网页与上游源码未变，沿用前轮结果，冻结清单记录各检查时间。连接与删除的范围见[操作记录](CONNECTION_AND_REMOVAL.md)，上一轮FTP数据流对照见[性能记录](FTP_PERFORMANCE.md)，界面规则见[视觉验收](VISUAL_QA.md)

本机JDK17.0.8的C1编译器在lint分析Kotlin UAST时两次触发`assembler_aarch64.hpp:267 / Field too big for insn`。仅对lint检查进程排除该分析方法的JIT编译后通过，未关闭lint规则或更改APK编译参数。诊断保留在`android/hs_err_pid39027.log`与`android/hs_err_pid41218.log`，这些日志不提交。复现同类JDK错误时可临时运行：

```sh
JAVA_TOOL_OPTIONS='-XX:CompileCommand=exclude,org.jetbrains.uast.kotlin.FirKotlinUastResolveProviderService::getArgumentForParameter' python3 scripts/check.py android-lint
```

## 验证边界

- 协议测试通过ADB反向端口连接本机模拟相机，模拟器到电脑Wi-Fi路由不可用；结果只证明JNI、协议和界面通路，不证明真实无线兼容
- 模拟相机明确标记QA SIMULATED CAMERA；测试程序不包含在Android包内
- 实际Android运行环境是4KB页模拟器；库和包对齐已经检查，16KB页系统运行尚未验收
- 云端实测为H2 MySQL模式和本地私有文件。MySQL/S3适配已实现，实际MySQL/MinIO/S3、HTTPS与公网交付未验证或部署
- A7C II具体固件、真实蓝牙、HEIF/ARW预览、千张目录、锁屏、OEM省电、卡切换和连续传输仍需真机
- 没有同机同网络同照片对照数据，没有发布提速、耗电或延迟提升结论。三秒首屏、二百毫秒缓存切图仍是目标
- FTP客户端本机测试使用Apache FtpServer；生产FTPS服务器、SAF系统文件提供方与OEM后台策略仍需实机验收
- 玻璃折射在Android15模拟器验收；旧Android模糊/半透明降级、真实GPU性能与耗电尚未实机验收
- 0.1.3尚未在实体手机安装验收；需要继续验证A7C II访问身份验证、实际文件提供方的删除授权与锁屏恢复
- APK使用Android Debug证书。正式私钥签名、商店提交、云端上线及正式产品发布未执行
- 代码协作分支为feature/framelink-v1，保留上游历史；源码分享与正式发布分别管理，协作方式见REVIEW.md

## 交付物

- artifacts/FrameLink-0.1.3-debug.apk：可安装开发包
- artifacts/FrameLink-0.1.3-source.zip：源码、上游、许可证和构建脚本
- artifacts/artifact-manifest.json：散列、证书、检查和待验收标记
- artifacts/android-home-initial.png、android-gallery.png、android-live.png、android-captured.png、android-local-review.png：原生运行截图
- artifacts/android-home-bento.png、android-ftp-receiver.png、android-ftp-client.png、android-photo-glass.png：0.1.1原生运行截图
- artifacts/visual-phone-*.png、visual-small-*.png、visual-gesture-*.png：0.1.3真实摄影样片界面；代表截图随README保存在docs/images
- artifacts/camera-identity-*.png、camera-connection-password-visible.png、photo-removal-choices.png：0.1.3本机合成设备的连接确认、密码显隐与移除操作截图
- artifacts/web-owner-desktop.png、web-client-mobile.png：网页真实交互截图

按[真机清单](HARDWARE_ACCEPTANCE.md)继续记录设备、固件和网络；M0实体机通过前保持开发测试版，不写成完整V1业务交付成功
