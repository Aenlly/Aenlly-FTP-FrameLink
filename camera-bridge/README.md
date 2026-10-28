# 原生相机与FTP协议桥

本模块将固定版本alpha2go导出为Android AAR及iOS XCFramework，Java包名为`app.framelink.nativecamera.bridge`，Objective-C前缀为`FL`

原片写入应用提供的文件路径，跨语言接口只传元数据、预览帧和进度。失败下载保留`.part`；文件目标必须按相机资源身份区分。相机删除、设置恢复和固件操作没有导出

构建：`scripts/build-android.sh`或`scripts/build-ios.sh`。本地回归：项目Go运行时执行`go test ./...`和`go test -race ./bridge`，并在`third_party/alpha2go`运行上游测试

Go FTP模块供iOS使用：只接收服务保存唯一原片、SHA256与持久化回执后回复成功；FTP／显式FTPS客户端使用256KiB流式传输并校验下载长度。Android继续使用原有Apache FTP实现。认证、同名保护、字节往返与证书拒绝在本机回环测试中验证，不能代替机身FTP兼容测试

源码支持不代表A7C II真机认证，见`docs/IMPLEMENTATION.md`
