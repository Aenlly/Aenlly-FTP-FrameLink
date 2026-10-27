# FTP数据流优化记录

2026-10-27，FrameLink 0.1.2开发测试版。测试范围为本机文件I/O与FTP命令，不包含真实相机无线传输

## 改动

- 接收端向Apache FtpServer传入256KiB的`BufferedOutputStream`。Apache会复用这个实例，替代默认8KiB输出缓冲，减少文件写入调用；没有修改依赖JAR、反射私有字段或替换第三方类
- 接收原片依次完成缓冲刷新、文件fsync、长度检查、唯一文件改名、目录同步与Room任务提交后，才回复226。校验、图库和压缩副本仍在后台处理
- FTP客户端下载与上传的数据流缓冲由64KiB调整为256KiB。下载同时计算SHA-256，避免成功下载后为生成照片身份再次读取原片；已有文件的重试路径仍重新校验
- 文件身份、目录编码、FTPS证书验证、同名保护、取消和从头重试规则沿用原有实现

这里的8KiB指Apache的**接收输出缓冲**。Apache内部4KiB复制块、输入缓冲和系统TCP缓冲没有改成256KiB，内存占用也不会随原片大小增长

曾测试在接收时同步计算指纹：本机文件完成后处理更早，但226回复更晚。最终保留ACK后的接收校验，避免让相机连续发送等待校验。客户端下载没有这一接收ACK约束，因此保留流式指纹

实现依据：[Apache IODataConnection](https://github.com/apache/mina-ftpserver/blob/1.2.0/core/src/main/java/org/apache/ftpserver/impl/IODataConnection.java)、[Apache IoUtils](https://github.com/apache/mina-ftpserver/blob/1.2.0/core/src/main/java/org/apache/ftpserver/util/IoUtils.java)。单测额外核对Apache实际返回的是传入的缓冲实例

## 本机对照

环境：Apple M4、macOS 27.0.1、JDK17.0.8、Apache FtpServer1.2.0、Commons Net3.13.0，连接地址127.0.0.1。测试期间没有并行运行构建或安装包回归

同一份固定随机数据，33,554,505字节（32MiB＋73字节），每条路径预热2轮后测7轮；交替先后顺序。两条路径都在落盘后回复226，再读取文件计算SHA-256；每次均核对字节数和摘要。每次文件均执行fsync，不代表冷磁盘或持续吞吐测试

| 指标 | 原路径 | 当前路径 |
|---|---:|---:|
| 文件输出write调用次数/次 | 4097 | 129 |
| 从上传开始到客户端收到226，中位数 | 21.797ms | 14.705ms |
| 从上传开始到落盘与摘要完成，中位数 | 114.243ms | 106.723ms |
| 上述完成时间折算速度 | 280.11MiB/s | 299.84MiB/s |

逐轮结果，单位ms：

| 轮次 | 原路径226 | 当前路径226 | 原路径含摘要 | 当前路径含摘要 |
|---|---:|---:|---:|---:|
| 1 | 29.555 | 17.159 | 120.597 | 108.763 |
| 2 | 31.143 | 14.561 | 124.254 | 104.510 |
| 3 | 28.189 | 15.264 | 119.713 | 106.723 |
| 4 | 21.797 | 14.491 | 114.243 | 106.248 |
| 5 | 21.375 | 15.787 | 113.927 | 107.643 |
| 6 | 21.378 | 14.637 | 113.701 | 108.473 |
| 7 | 20.930 | 14.705 | 113.932 | 106.300 |

本机结果只说明输出调用减少，以及这一组条件下的耗时差异。统计不含Room、MediaStore、预览、Android文件系统和无线链路，不能据此公布A7C II传输速度或耗电提升

## 复现与回归

先按[构建说明](../README.md#快速开始)准备环境，然后在项目根目录运行：

```sh
python3 scripts/check.py android-tests-build
python3 scripts/check.py ftp-benchmark
```

基准调用已编译的生产`FtpTransferIo`和`FtpConnection`，仅启动本机临时FTP服务器，临时目录结束后删除。依赖取自Android构建的Gradle缓存，不连接相机或外部FTP服务器。日志与退出码分别保存在`build-logs/ftp-benchmark.log`、`build-logs/ftp-benchmark.exit`。调整文件大小和轮数可直接运行`python3 scripts/benchmark-ftp.py 32 7`

回归覆盖：

- JVM：跨多个缓冲区及尾块、短读取与零长度读取、空文件、原始字节与摘要、取消、断流、写入失败、调用方流的所有权
- Android：大于4MiB的多块原片接收、同名原片分离、226后任务与文件已持久化；客户端下载摘要、中文目录、上传同名保护、队列、取消与重试
- 基准额外使用生产FTP客户端检查下载字节与摘要、上传字节、拒绝覆盖已有文件

相机同条件连续发送、真实FTPS服务、实体手机存储与锁屏测试仍按[真机清单](HARDWARE_ACCEPTANCE.md)执行
