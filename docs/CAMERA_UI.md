# 取景交互

Android0.1.16按已确认的v5继续重排取景，参考此前提供的澎湃OS4录屏与iPhone相机层级。使用项目自己的图标和材质，不引入参考产品的品牌素材。

- 拍照页保留快门、相册、延时和控制菜单；专业模式显示实际返回的f／S／ISO／EV／WB／AF
- 竖屏以大快门和底部模式胶囊为主，紧凑调节面板位于操作区上方；横屏把相册、快门、模式和六项快捷参数集中到右侧
- 取景保留相机原比例。横屏利用剩余宽度安排控制，开关菜单、拍照／专业切换均不改变取景区域
- 数值调节使用可直接拖动的块状滑块，候选值来自机身；拖动时更新预览数值，松手后只提交一次，机身未确认时回到已确认值
- 网格、直方图、峰值、过曝提示、延时和拍摄设置集中在玻璃菜单；白平衡、对焦等枚举使用中文选择面板
- 工具菜单默认打开“机身参数”，锚定右上方，竖屏最大268dp、横屏最大238dp，按内容决定高度；普通字号四列，大字或窄窗自动减列，单项至少44dp可触控，超过可用高度时内部滚动
- “专业”切换的是手机操作布局，不擅自将相机改为M档；不支持的机身控制不会作为可用按钮展示

预览帧仍由相机连接通道决定，显示区域变大不等于实收分辨率提高。软件回归、手机截图、镜头实际动作分别记录在IMPLEMENTATION.md和HARDWARE_ACCEPTANCE.md。

底部仅消费一次实际手势／切口inset，不额外叠加通用Dialog的导航栏回退高度。不同导航方式需要分别核对，本页截图使用手势导航。

## 界面状态

以下为Android模拟器中的授权样片模拟视频流，不是相机或iPhone真机验收。

| 状态 | 亮背景 | 暗背景 |
|---|---|---|
| 竖屏拍照 | [截图](images/camera-ui/bright-portrait-photo.png) | [截图](images/camera-ui/dark-portrait-photo.png) |
| 竖屏专业 | [截图](images/camera-ui/bright-portrait-pro.png) | [截图](images/camera-ui/dark-portrait-pro.png) |
| 竖屏滑块 | [截图](images/camera-ui/bright-portrait-ruler.png) | [截图](images/camera-ui/dark-portrait-ruler.png) |
| 横屏拍照 | [截图](images/camera-ui/bright-landscape-photo.png) | [截图](images/camera-ui/dark-landscape-photo.png) |
| 横屏专业 | [截图](images/camera-ui/bright-landscape-pro.png) | [截图](images/camera-ui/dark-landscape-pro.png) |
| 横屏滑块 | [截图](images/camera-ui/bright-landscape-ruler.png) | [截图](images/camera-ui/dark-landscape-ruler.png) |
| 竖屏机身参数 | [截图](images/camera-ui/bright-portrait-menu.png) | [截图](images/camera-ui/dark-portrait-menu.png) |
| 横屏机身参数 | [截图](images/camera-ui/bright-landscape-menu.png) | [截图](images/camera-ui/dark-landscape-menu.png) |
| 竖屏预览辅助 | [截图](images/camera-ui/bright-portrait-assist-menu.png) | [截图](images/camera-ui/dark-portrait-assist-menu.png) |
| 横屏预览辅助 | [截图](images/camera-ui/bright-landscape-assist-menu.png) | [截图](images/camera-ui/dark-landscape-assist-menu.png) |

模拟器连续流程覆盖进入取景、滑动调参、收起、点按对焦、快门、进入图库、返回取景和旋转。运行`python3 scripts/instrument.py live-flow`，截图与构建散列通过`python3 scripts/collect-camera-ui.py --theme bright`采集。样片出处见[视觉记录](VISUAL_QA.md)。

0.1.16连续录屏由`python3 scripts/record-live-flow.py`采集。普通字号每种背景10图；1.5倍字体通过`python3 scripts/instrument.py live-menu`检查横竖屏的两个标签，再用`collect-camera-ui.py --theme bright --variant large`采集，每种背景4图。明暗状态齐备后运行`python3 scripts/freeze-camera-ui.py`，核对同一主包、诊断组件、28张截图与录屏散列，记录为`artifacts/camera-ui-0.1.16-evidence.json`。0.1.15冻结记录保留；协议模拟器不替代手机与镜头实测。
