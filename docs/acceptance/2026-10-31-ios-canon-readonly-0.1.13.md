# iOS Canon CCAPI只读软件适配

2026-10-31，iOS0.1.13／build14源码候选。仅使用独立本机合成HTTP服务、Swift Core及源码检查；不连接手机、平板或相机，不请求实际拍摄，不触发Mac认证，旧包与记录保留。本轮不增加iOS USB、云、收费或其他品牌业务。

## 实际路径

CameraConfig把protocolID编码为共享Go需要的protocol，NativeTransport.connect和测试夹具使用同一个CameraConnectionPolicy.encode。旧配置缺少protocol时兼容为sony-ptpip；未知、空或null协议不能静默进入Sony。连接页增加Canon CCAPI实验通道，默认端口8080，HTTP认证按机身设置；Canon不走Sony身份查询，wire中清除Sony指纹及配对GUID。

协议参与连接匹配、保存、更新、忘记和端点编辑。保留旧Sony连接ID的“sony”存储命名空间；Canon另用canon-ccapi命名空间。同端点的两个协议互不覆盖，切通道清当前账号密码／指纹，新连接默认不保存。未保存Canon凭据只保留当前会话，不抹除Sony记录。

共享CCAPI后端从真实GET端点发现能力及deviceinformation，从序列号／GUID构建设备身份；缺少两者时拒绝连接和索引，不拿型号当唯一身份。iOS保留原始能力报告，但客户端Canon能力限制为浏览、预览和下载；不查询Sony属性，禁用Canon取景、参数写入及快门。没有媒体端点时显示能力边界，不假称已可浏览。

FILE_MODIFIED、fileModifiedAt和fileDay按后端原义解析。capturedAt为0时保持未知，不将文件时间写成拍摄时间；文件时间只作排序回退并在范围和文件页明确标识。照片EXIF仍只读文件实际记录。

范围总数／后台轻量索引、缩略图、大图JPEG临时缓存及取消安全的导入发布复用既有实现。资源ID包含稳定设备身份和文件修订，JPEG与CR3各自保存；RAW不复用JPEG缓存。

## 验证

- Swift Core：57项零失败，新增8项覆盖旧配置兼容、未知协议、实际JSON字段、Canon免SSH、无指纹重连、协议切换清凭据、同端点保存独立、旧Sony ID不变、未保存Canon账号、只读能力和文件日期口径
- 主机跨语言链路：编译FrameLinkConnectionFixture，由Go测试把服务动态IP／端口传给Swift；Swift按NativeTransport的真实编码器产生JSON，原样交给Session.Connect，真实共享Canon HTTP后端负责认证、发现与媒体读取，不另造协议实现
- 六个跨语言子场景通过，无跳过：成功链路、错误HTTP认证、未知协议客户端拒绝、缺少稳定身份拒绝、相同型号不同序列号资源身份不同、发现端点缺失报错
- 成功链路验证机身信息、文件日期列表、范围总数、RAW＋JPEG合组、缩略图及显示预览、JPEG／CR3原字节与重连；合成服务拒绝所有非GET，确认整个只读链路没有写入请求
- Go返回的真实信息／分页JSON及下载文件再传回Swift：实际解码、JPEG缓存提交与命中、使用同一CameraImportPublication发布JPEG和RAW、逐文件摘要一致且不推断拍摄时间。JPEG缓存命中时下载替身若被调用会直接失败，因此不是只验证枚举或型号矩阵
- 另运行既有Digest向量、实际认证与原字节、重定向不转发凭据、逐方法发现及外部／未公布端点拒绝验证，全部通过
- 从当前真实Go源码生成ObjC头文件后，Core模块、App Swift及UI测试源码类型检查通过；保留切到Canon后清理Sony凭据的UI用例，但没有运行UI

Core日志为`ios-0.1.13-core-final`，HTTP专项为`ios-0.1.13-ccapi-final`。跨语言测试要求FRAMELINK_SWIFT_CONFIG_FIXTURE指向已编译工具；未提供时Go会标记跳过，本轮已明确注入并验证无SKIP。合成CR3仅用于原字节传输，不是有效RAW解码或EOS850D兼容证明。

证据归档：`artifacts/FrameLink-0.1.13-ios-evidence/`，记录源码、日志、Swift夹具身份和源码检查摘要；独立源码ZIP保留。

## 尚未交付的运行结果

本轮未重生成Go二进制xcframework、未链接新版App、未生成新IPA、未运行模拟器UI或iPhone。当前可供自签的设备包仍是0.1.10，不含Canon通道。管理员条件沿用原一次待办，不再弹窗；授权后必须先重生成真实框架再验证App，不能仅替换旧头文件宣称升级。

EOS850D固件、CCAPI启用条件、机身认证与实际传输、真实JPEG／CR3及网络恢复均待实机。Canon取景、曝光设置、对焦、拍摄及更多机型不能从共享Go接口或官方名单推导为iOS已通过。
