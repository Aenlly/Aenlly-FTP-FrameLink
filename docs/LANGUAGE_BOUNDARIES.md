# Java、Kotlin与共享协议代码的取舍

按职责与维护收益选择语言。已有照片、选择、任务和连接信息的行为必须保留；语言调整不能靠清库或卸载解决问题。UI和内部API可以重整，但不机械重写所有文件。

## 本轮已进入生产调用的Java

| 代码 | 职责与替换理由 |
|---|---|
| `support/CameraSupportRegistry.java` | 协议注册、系列profile、型号/固件匹配和能力交集；无Android依赖，规则与边界集中，Java维护者可直接修改和测试 |
| `support/FujiDiscoveryRules.java` | 厂商广播解析、无名称识别、候选去重与扫描结束保护；先验证厂商ID及载荷，不把其他厂家同形数据当作Fuji配对token |
| `support/FujiGattProtocol.java` | 配对字段与允许写入的校验；不携带Android对象，便于审查协议安全边界 |
| `support/CameraPairingStore.java` | 配对凭据的存储契约；按协议和设备隔离，驱动不直接依赖加密设置实现或云账号 |
| `PhotoSelectionRange.java` | 滑动选片的范围计算；替换原`PhotoSelection.kt`。固定输入快照和顺序，回滑恢复范围外原选择，连拍组一起处理；Compose手势仍用Kotlin |

profile是协议差异配置，不是用户保存的设备账号。新增型号优先补有来源的差异，不复制整份驱动；配置不能把未知或只读能力提升为可写。

## 保留Kotlin

| 代码/模块 | 原因 |
|---|---|
| `AppUi`、取景、大图、玻璃组件及窗口布局 | 与Compose、状态、手势和布局直接相关；转Java会增加适配层和样板代码 |
| `FujiPhoneLink`、`AndroidUsbPhotoAccess`、`BluetoothRemote` | 负责系统对象、回调、权限、协程取消和生命周期；规则已移到Java，平台接线继续用Kotlin |
| Room实体、DAO与协程仓库 | 依赖KSP、挂起查询和不可变状态更新；没有为改语言而迁移数据库或改字段语义 |
| `PhotoExif`、`PhotoRemoval` | 使用Android内容解析、EXIF和系统删除授权；平台逻辑留在对应实现中 |
| `ConnectionBook`与`SecureCameraPairingStore` | 是持久化适配层，复用当前加密设置；凭据规则和存储契约已有独立边界，无需整体翻译成Java |
| `OriginalPreviewCache` | 虽主要使用Java IO，但代码还直接消费当前照片模型和资源身份。当前保留小而内聚的实现；有实际复用需求时再抽出缓存策略，不能因此改变2GB上限、JPEG预览语义或正式导入校验 |

## 继续优化的方向

`CameraRuntime`仍集中部分本地用例和云调用，`AppUi`仍包含较多页面。后续优先按连接、媒体、选片、传输和存储职责拆分，避免先把大文件逐行翻成Java。抽出的纯规则可用Java，状态与平台接口按其适用语言保留。

跨Android/iOS复用的传输、封包、文件校验和相机协议继续在Go层维护；iOS系统接口与界面使用Swift。新的跨端字段和状态必须连同实际调用、测试和数据迁移一起处理，不能只维护两份相似而逐渐分歧的接口表。

每次替换先检查真实调用方、排序和快照语义、异常边界及存储影响，再跑相关验证。本轮保留了原多选回归，并补充调用方修改输入后拖动快照不变和选择顺序稳定的用例；没有为了增加Java文件数量重写已正常工作的页面。
