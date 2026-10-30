# iOS分页请求意图区分

2026-10-31，iOS0.1.12／build13源码候选。保留0.1.11源码与证据、既有iOS及Android包；本轮不触发安装认证、不连接真实设备、不重跑Android。

## 缺陷与修正

0.1.11近尾回调在排入Task前检查error，但AppModel进入分页时统一传retry:true。已有flight恰好先失败时，迟到自动Task会清掉刚发布的错误并重试。Core默认停止错误状态本身没有问题，缺陷在上层调用意图混用。

本轮仅分开请求意图，保留原视觉、范围总数、元数据、选择和索引合并逻辑：

- AppModel.loadCamera必须显式传入CameraIndexLoadIntent，并原样转交Core的requestNext(intent:)入口，不再统一传retry:true
- 连接后的初始读取和网格近尾明确传automatic；实际执行到入口时，若已发生错误，不清错误、不重新取页；自动路径不重复弹出错误提示
- 重试按钮明确传userRetry；刷新按钮传refresh并先重置范围快照。用户点击全选仍按主动操作处理，可在需要时明确恢复索引读取
- 背景补齐仍使用不允许重试的原流程，同范围无错时共享flight；旧分页、范围和状态保留断言全部保留

## 验证

`ios-0.1.12-core-release`退出0，49项核心测试零失败。新增可控时序用例使用与AppModel相同的typed入口：

1. 首批100／范围总250，背景取游标100并挂起
2. 在error仍为nil时排入automatic动作，但用独立门闩阻止其执行
3. 恢复背景请求，让它先失败、发布error并清理flight
4. 放行已排队automatic动作，确认请求序列仍为0／100，错误不被清除，已读100条及未知总数状态保留
5. 明确执行userRetry后，才继续100／200并补齐250

此用例不是只重测旧Core默认值；新增入口负责意图到重试权限的转换，App实际转交该意图，UI三个调用点分别传入automatic／userRetry／refresh。App、Core模块和UI测试源码在真实Go生成头文件下的类型检查通过。

完整Core及源码检查日志／摘要存于`artifacts/FrameLink-0.1.12-ios-evidence/`。本轮没有重生成Go二进制框架、没有App链接、没有新IPA、没有UI运行或实体结果。仍沿用原管理员待办，授权后再完成框架更新与隔离UI验收，不把旧设备包当作本轮结果。
