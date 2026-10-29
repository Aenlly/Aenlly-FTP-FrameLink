# 取景交互

Android0.1.12参考用户提供的澎湃OS4录屏、iPhone相机截图及[澎湃OS4官网](https://os.mi.com/)的柔光玻璃表达，重排拍照与专业界面。使用项目自己的图标和材质，不引入参考产品的品牌素材。

- 拍照页保留快门、相册、曝光、延时和控制菜单；专业模式再展示机身曝光参数
- 竖屏以大快门和底部模式胶囊为主，数值调节出现在快门下方；横屏把相册、快门与参数尺集中到右侧操作区
- 取景保留相机原比例。横屏利用剩余宽度安排控制，开关菜单、拍照／专业切换均不改变取景区域
- 数值调节使用可直接拖动的块状滑块，候选值来自机身；拖动时更新预览数值，松手后只提交一次，机身未确认时回到已确认值
- 网格、直方图、峰值、过曝提示、延时和拍摄设置集中在玻璃菜单；白平衡、对焦等枚举保留完整中文列表
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
| 竖屏工具 | [截图](images/camera-ui/bright-portrait-menu.png) | [截图](images/camera-ui/dark-portrait-menu.png) |
| 横屏工具 | [截图](images/camera-ui/bright-landscape-menu.png) | [截图](images/camera-ui/dark-landscape-menu.png) |

模拟器连续流程覆盖进入取景、滑动调参、收起、点按对焦、快门、进入图库、返回取景和旋转。运行`python3 scripts/instrument.py live-flow`，截图与构建散列通过`python3 scripts/collect-camera-ui.py --theme bright`采集。样片出处见[视觉记录](VISUAL_QA.md)。

0.1.12本地连续录屏为`artifacts/camera-ui-0.1.12-flow.mp4`，约23秒；同包主／诊断散列与16张截图清单记录在`artifacts/camera-ui-0.1.12-evidence.json`。整轮32项、暗图取景3项与连续流程1项通过。上述结果来自协议模拟器，不替代手机与镜头实测。
