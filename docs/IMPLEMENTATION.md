# 实施与验收报告

当前版本：光屿FrameLink 0.1.2开发测试版

**源码实现、安装包构建和本地软件验收已完成；完整V1的A7C II实体机与发布验收尚未完成。** 没有连接用户相机、拍摄用户真实照片、删除卡内数据或公开部署服务

## 已开发能力

| 子系统 | 实现范围 |
|---|---|
| Android | 相机、图库、项目、传输，连接引导、日期和筛选、RAW/JPEG分组、对比、标记、原生取景、专业辅助、倒计时与拍后查看 |
| 协议 | 锁定alpha2go，Android AAR，SSH指纹、独立相机socket绑定、卡内分页、预览、分块原片下载、参数候选值和取景 |
| FTP | 只接收、二进制原片、唯一文件、fsync、任务持久化后回复226，重启恢复已落盘文件；图库处理独立排队 |
| FTP客户端 | FTP/显式FTPS、加密账号、目录与搜索、上下级、多选下载、上传、新建文件夹、任务进度、取消和重试 |
| 接收配置 | 自定义端口、系统授权保存目录、独立压缩副本、JPEG质量和尺寸、格式范围、副本相册同步 |
| 界面 | 紧凑连接状态、两项主要操作与四项快捷工具、最近照片、密集图库、深浅场景主题、加载失败与重试、克制玻璃浮层 |
| 蓝牙 | 扫描、配对、订阅、半按与快门、连接状态、上次设备、通知栏入口 |
| 本地 | Room照片与任务、原片与相册URI分开、预览缓存、取消重试、Room1→2迁移 |
| 现场选片 | 无需登录，独立场次、客户选择留言、锁定和重开、CSV导出；与摄影师收藏和标记分开 |
| 云服务 | Java17/Spring Boot、邀请注册、会话摘要、隔离、配额、私有资源、预览采样、EXIF方向与定位移除 |
| 客户和交付 | 访问码、自动保存、设备草稿、冲突、提交版本、确认与重开、关联成片、下载授权、撤销 |
| 工作台同步 | 轻量版本检查；仅变更后读取详情，返回窗口和恢复联网时同步 |

本地连接、导入、标记和现场客户选片不依赖云账号。云上传仅处理主动选定的预览或成片，默认等待非计费网络

## 已验证

| 层级 | 结果 | 证据 |
|---|---|---|
| Android构建 | 通过；app.framelink.camera，min26/target36，arm64/x86_64 | build-logs/android-tests-build.log |
| Android JVM | 9项通过：原有5项，另加FTP流的多块与尾块、短读取、空文件、取消与失败、原始字节及摘要 | android/app/build/test-results/testDebugUnitTest/ |
| Android安装运行 | Android15/API35、arm64独立FrameLinkQA模拟器 | build-logs/android-instrument.log |
| Android验收 | 9项通过：原有6项，加FTP客户端协议、持久化队列与重试、实际界面连接到下载及大图 | OK (9 tests) |
| FTP数据流对照 | 同一32MiB＋73字节文件，预热后每条路径7轮；输出write调用4097→129，字节与摘要均通过 | [条件与逐轮数据](FTP_PERFORMANCE.md)；build-logs/ftp-benchmark.log |
| 0.1.1小屏记录 | 360×640dp、字体120%，FTP连接下载到大图流程通过，四个主要入口首屏可见；本行是上一版记录 | build-logs/android-instrument-small-font.log；artifacts/android-home-small-large-font.png |
| 0.1.2摄影界面 | 3项通过：默认屏、小屏120%字体、手势导航；授权真实样片、六个入口、多选、完整比较、大图缩放像素、末张照片与系统区域 | build-logs/android-visual-phone.log、android-visual-small.log、android-visual-gesture.log |
| Go桥 | 3项通过，含5MiB RAW/JPEG分组与字节一致性、不锁机身模式 | build-logs/bridge.log |
| 上游 | 5个包测试通过 | build-logs/upstream.log |
| 云服务 | 11项通过：并发版本、历史、链接失效、配额回滚、交付权限、上传前鉴权、方向与元数据 | cloud/target/surefire-reports/ |
| 网页 | 类型和生产构建通过，5项自动保存队列测试通过 | build-logs/web.log |
| 网页端到端 | 8个真实交互场景通过；1440×1000和390×844；上传、刷新保留、自动同步、成片字节、撤销 | artifacts/web-e2e-results.json |
| Android静态 | lintDebug通过，0错误；仍有依赖更新及第三方代码警告 | android/app/build/reports/lint-results-debug.xml |
| 原生库和包 | ELF LOAD段0x4000对齐，冻结时另验16KB ZIP对齐和证书 | artifacts/artifact-manifest.json |

测试使用本地合成图片和测试账号，没有真实客户照片、联系人或机身密码。完整成功与失败日志保存在build-logs，失败日志按时间归档

0.1.2包含Android界面、可访问加载反馈、摄影样片验收、中英日README，以及后续FTP数据流优化。FTP接收输出缓冲和客户端下载校验的实现与对照见[FTP性能记录](FTP_PERFORMANCE.md)，文件身份与任务状态规则保持兼容。核心数据模型、相机和云端权限接口没有变更。云端、网页、Go与三种规格视觉验收沿用前一轮验证，本轮重跑Android构建、JVM、安装回归和lint；冻结清单分别记录各检查日志时间。FTP功能对应关系见[FTP记录](FTP_AND_UI.md)，界面规则与验证方法见[视觉验收](VISUAL_QA.md)

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
- 0.1.2尚未在实体手机安装验收；手机与A7C II的实测安排在后续阶段
- APK使用Android Debug证书。正式私钥签名、商店提交、云端上线及正式产品发布未执行
- 代码协作分支为feature/framelink-v1，保留上游历史；源码分享与正式发布分别管理，协作方式见REVIEW.md

## 交付物

- artifacts/FrameLink-0.1.2-debug.apk：可安装开发包
- artifacts/FrameLink-0.1.2-source.zip：源码、上游、许可证和构建脚本
- artifacts/artifact-manifest.json：散列、证书、检查和待验收标记
- artifacts/android-home-initial.png、android-gallery.png、android-live.png、android-captured.png、android-local-review.png：原生运行截图
- artifacts/android-home-bento.png、android-ftp-receiver.png、android-ftp-client.png、android-photo-glass.png：0.1.1原生运行截图
- artifacts/visual-phone-*.png、visual-small-*.png、visual-gesture-*.png：0.1.2真实摄影样片界面；代表截图随README保存在docs/images
- artifacts/web-owner-desktop.png、web-client-mobile.png：网页真实交互截图

按[真机清单](HARDWARE_ACCEPTANCE.md)继续记录设备、固件和网络；M0实体机通过前保持开发测试版，不写成完整V1业务交付成功
