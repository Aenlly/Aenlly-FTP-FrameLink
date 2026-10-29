# Android13全屏兼容记录

设备为红米23013RK75C，Android13/API33。系统当前设置为1080×2400、420dpi，原生面板为1440×3200；验收保留原有显示设置。

0.1.6在此设备已完成实际相机浏览、选择、导入和重连；取景检查发现竖屏底部会露出主页面，旋转阶段的窗口边界也可能仍在变化。

## 全屏测量修复

工程使用的Compose UI为1.7.8。其DialogLayout在usePlatformDefaultWidth=false时使用screenWidthDp／screenHeightDp测量，并在布局后将窗口固定为子View的测量尺寸；旧系统的这些配置值排除了部分系统栏区域，会覆盖窗口的MATCH_PARENT设置。

0.1.7保留平台测量，再显式设置窗口MATCH_PARENT；旋转仍更新同一个窗口。FTP客户端复用全屏容器，避免维护另一套尺寸处理。此修复不改动文件格式、下载队列和数据库结构；同版相机目录加载的变化见[GALLERY_BROWSING.md](GALLERY_BROWSING.md)。

依据：[Compose UI1.7.8官方源码包](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.7.8/ui-android-1.7.8-sources.jar)中的AndroidDialog.android.kt，DialogLayout.internalOnMeasure／internalOnLayout。

## 诊断方式

这台设备的USB安装与系统触控注入受限，采用手动安装APK。诊断组件新增应用窗口事件分发模式，仅向光屿进程中已聚焦的窗口派发事件；不会向其他App窗口发送输入。这种结果应与系统触屏层的人工体验分开记录。

```sh
python3 scripts/check-physical.py --serial <设备序列号> \
  --allow-camera-parameter-check --manual-install --app-touch --include-ftp
```

诊断组件使用可选反射访问UiAutomation.clearCache，兼容旧系统；全屏边界必须连续稳定后再检查，维持两个物理像素的取整容差。主包与诊断包均需使用对应的最新构建。

验证结果以HARDWARE_ACCEPTANCE.md及本机完整日志为准，截图和相机配置备份不提交Git。
