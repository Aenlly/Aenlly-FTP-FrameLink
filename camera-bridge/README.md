# Android相机协议桥

本模块将固定版本alpha2go导出为gomobile AAR，Java包名为`app.framelink.nativecamera.bridge`

原片写入Android提供的应用内文件路径，只通过JNI传输元数据、预览帧和进度。失败下载保留`.part`；文件目标必须按相机资源身份区分。相机删除、设置恢复和固件操作没有导出

构建：`scripts/build-android.sh`。本地回归：项目Go运行时执行`go test ./...`，并在`third_party/alpha2go`运行上游测试

源码支持不代表A7C II真机认证，见`docs/IMPLEMENTATION.md`
