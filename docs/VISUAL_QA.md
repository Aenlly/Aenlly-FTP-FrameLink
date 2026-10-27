# 0.1.2界面与摄影样片验收

本页先记录基于`bb16fe8`完成的0.1.2摄影界面调整。0.1.3继续沿用该视觉框架，新增连接身份确认、密码显隐、固定底部按钮和照片移除弹窗；Room迁移与功能范围见[连接与照片管理](CONNECTION_AND_REMOVAL.md)。不以截图或模拟器结果替代A7C II实体机验收

## 设计方向

照片优先的摄影工具，参考[Halide](https://www.lux.camera/halide-mark-iii/)的拍摄层级、[Darkroom](https://darkroom.co/help/guide/interface-overview)的图库与大图组织，以及[Apple材质说明](https://developer.apple.com/design/human-interface-guidelines/materials)对浮动控件的处理。使用Android系统字体与Material交互，不使用Apple专有控件、字体或图片资源

| 用途 | 规则 |
|---|---|
| 连接、图库、项目、传输和设置 | 灰白背景`#F5F5F7`，白色工作卡片，柔和蓝色强调 |
| 取景、大图与比较 | 深色场景和统一的浅色操作控件，照片维持视觉主次 |
| 字体 | 页面标题20–22sp，功能13–16sp，照片辅助信息9–11sp；跟随系统字体缩放 |
| 圆角 | 工作卡片18dp，常规控件12dp，照片8dp，玻璃工具栏按用途20–26dp |
| 间距与触控 | 页面16dp，模块12–16dp，常用操作保留Android触控区域 |
| 玻璃 | 用于底部导航与大图工具栏；减少边框、高光与阴影，按钮文字不参与模糊 |
| 动效 | 主要操作按下轻微缩放，其余沿用原生反馈；不增加持续背景动效 |

首页以紧凑设备状态、两项主要操作、四项快捷工具和最近照片区分层级，六个原有入口保留。图库去掉重复品牌和大标题，采用紧凑控制区与更密的照片网格，保留文件名、来源与状态

浅色导航使用较强的背景模糊和浅色遮罩，避免下方文件名干扰导航文字。深色照片工具栏限制背景亮度，以适应亮天空和复杂夜景。照片放大时限制绘制边界，避免覆盖系统状态栏和导航区域。旧系统保留背景模糊或半透明降级路径

## 功能保留与反馈

- 首页：选片下载、取景拍摄、FTP接收、FTP客户端、快速快门、本机导入均保留，回调接入原有流程
- 图库：来源、日期、全部/已选/收藏/未导入/RAW、连拍分组、跟随最新、多选、对比、下载、项目与现场客户模式均保留
- 大图：原片与预览提示、缩放、收藏、选中、排除、修图副本与系统分享均保留
- 缩放：改为与内容绘制同步的画布变换，修复玻璃背景捕获时缩放状态与实际图片不同步的问题；显示相对初始适配画面的倍数
- 比较：改为按完整画面适配显示，不裁切照片主体；选中行为保持原样
- 加载：缩略图与大图增加加载、解码失败和可访问状态；大图提供手动重试，复用原有预览接口
- 项目与传输：减少宣传文案和重复标题，保留账号、选片版本、进度、失败原因、重试和取消行为
- 取景：统一深色控件主题，原OpenGL画面、参数候选值、快门、倒计时和专业辅助保持原逻辑

## 授权摄影样片

样片来自各照片页面明确标注可按[Unsplash License](https://unsplash.com/license)使用的真实摄影作品。仅用于本地界面验收和项目截图；App不预装样片，不自动下载这些图片，也不上传到云服务

| 本地样片 | 作者 | 原始页面 |
|---|---|---|
| Sample-01-Lake.jpg | Ravi Pinisetti | [山湖](https://unsplash.com/photos/landscape-photo-of-lake-near-mountain-L16dgFLf8tU) |
| Sample-02-Forest.jpg | David Bruyndonckx | [林间道路](https://unsplash.com/photos/pathway-between-green-trees-F_hft1Wiyj8) |
| Sample-03-Snow.jpg | Katelyn G | [山与湖](https://unsplash.com/photos/a-lake-with-a-mountain-in-the-background-dyr4k2ciRHA) |
| Sample-04-Night.jpg | rawkkim | [夜间街景](https://unsplash.com/photos/a-city-street-at-night-with-a-lot-of-traffic-uHueMWQq4dw) |
| Sample-05-Street.jpg | Quang Nguyen Vinh | [街道与车流](https://unsplash.com/photos/a-city-street-with-a-traffic-light-at-night-ugvbGfazIV8) |
| Sample-06-City.jpg | Jesse Zegveld | [夜间车轨](https://unsplash.com/photos/a-city-street-at-night-with-traffic-lights-Z4YJHAUt3I8) |

照片按公开图片服务提供的JPEG尺寸用于显示，截图中可能出现裁切、缩放与界面叠加。作者和照片许可不因代码采用GPL而改变。样片URL、作者、许可和已核验SHA256记录在`scripts/visual-fixtures.json`；下载副本与实际散列位于本地`artifacts/visual-fixtures/`

样片通过正常导入流程进入独立FrameLinkQA模拟器。测试只调整这些样片的索引排序时间与收藏标记，便于重复检查相同画面；这些字段不是原始拍摄时间或真实用户记录

## 验收方式

```sh
python3 scripts/check.py android-tests-build
python3 scripts/visual-qa.py all
python3 scripts/dev.py simulator
# 模拟相机运行时，在另一个终端执行核心回归
python3 scripts/instrument.py
```

`visual-qa.py`只操作明确命名的FrameLinkQA设备：

| 规格 | 检查内容 | 完整日志 |
|---|---|---|
| phone | 默认尺寸、三按钮导航；首页、图库、多选、完整比较、三类背景大图、项目、传输和设置 | build-logs/android-visual-phone.log |
| small | 360×640dp、字体120%；六个首页入口、照片控制、底部操作与完整流程 | build-logs/android-visual-small.log |
| gesture | 系统手势导航；相同流程与最后一张照片的安全区域 | build-logs/android-visual-gesture.log |

测试会等待真实图片解码和原生窗口稳定后再截图，检查大图是否显示、已有操作是否可见、缩放后照片区域的实际像素是否变化，以及最后一张照片能否滚动到导航栏上方。退出时恢复该测试设备的默认尺寸、字号与三按钮导航。每种规格的日志、退出码与运行截图独立保存

界面入口和交互来自实际安装包，摄影样片不伪装成机身取景。核心九项功能回归与视觉用例分开执行，照片字节、任务恢复和相机模拟协议仍由原有功能验收覆盖

## 0.1.3连接与移除

- 连接表单采用灰白底色和白色卡片，机身设置按需展开，主要按钮固定在底部；密码支持显示或隐藏
- 首次指纹和变化提示保留完整公钥信息、统一圆角和间距；确认前不认证，已记住的信息下次直接使用
- 移除弹窗使用白色卡片，默认仅移出图库；删除本机文件采用独立确认文案与红色主要按钮
- 三种摄影界面规格追加真实样片背景下的移除弹窗检查，并在360×640dp、字体120%的设备上完成连接与确认流程
- 普通表单保持清晰，玻璃材质继续用于底部导航和照片浮层，不扩大到整页内容

## 截图入口

- [首页](images/home.png)、[图库](images/gallery.png)、[多选](images/selection.png)、[大图](images/viewer.png)
- [项目](images/projects.png)、[传输](images/transfers.png)
- 小屏、大字体、亮图和手势导航截图保存在本地`artifacts/visual-*.png`，结果由实施报告汇总

## 验证边界

本轮不重复将云端与网页的既有本地结果写成新的端到端验收。A7C II无线、蓝牙、实际系统文件提供方、旧Android降级、真实GPU性能和耗电仍需独立实机记录。没有同条件传输或取景对照数据，不发布性能提升结论

README组织方式参考了[LocalSend](https://github.com/localsend/localsend/blob/main/README.md)与[GKD](https://github.com/gkd-kit/gkd/blob/main/README.md)的产品介绍、截图、安装、开发与贡献入口，文案按本项目实际能力重新撰写
