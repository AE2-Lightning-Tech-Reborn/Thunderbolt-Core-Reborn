# 提供器缓存与执行输入边界

## 固定输入分配

内置规划器不再收集逐槽执行绑定，也不再自动调用 `PlannedInputAssignments.record`。
规划仍计算具体材料需求和配方次数，对外保留注册样板身份；执行 CPU 负责在实际库存与剩余
任务之间分配材料。LT 时间轮 CPU 已拥有相应分配器。

`PlannedInputPattern`、旧 `#plannedInputs` 解码、显式记录 API 和执行助手保留兼容，
不因停用自动生成而破坏旧任务读取或其他显式使用者。批次所有权不明时停止派发的保护也保留。

## 候选与能力缓存

每个物理 CPU 的 `TickProviderDispatchSchedule` 持有候选队列。候选按注册样板对象身份缓存，
稳定网络跨 tick 复用队列节点及最近成功的提供器；新 tick 只恢复上次拒收的节点，未拒收的
节点无需重新扫描或创建。忙碌状态、容量、配方准备和提交始终实时执行。

AE2 `NetworkCraftingProviders.setLastModifiedOnTick` 在节点/全局提供器挂载与卸载后调用。
Mixin 在该处维护修订号，经 `CraftingServiceAccessor` 暴露给调度器。缓存同时绑定 service
对象身份和修订号，因此同 tick 内多次增删、刷新样板，以及切换网络都使候选与能力缓存失效。
没有该修订接口的调用方保留原来每 tick 重新枚举的行为，不能仅用时间戳推断列表未改变。

`BatchProviderResolver.cacheResolutionAcrossTicks()` 默认 false。显式选择 true 的解析器
可以跨 tick 缓存成功与失败结果，但能力必须在提供器刷新之前保持稳定，包装不得持有任务。
缓存按解析器和提供器的对象身份区分。普通 `BatchProviderAdapter` 仍每次接收实际 job/pattern；
`cacheResolutionForTick=false` 仍绕过缓存。

首次在一个 tick 使用缓存的适配器端点前，会调用 `beginDispatchTick(tick)`。LT 的 NeoECO/
Useless 端点利用它清除临时的普通投递降级，不能把本 tick 拒绝 FastPath 永久缓存。
调用是惰性的，不在 tick 边界遍历所有已缓存提供器。

使用按身份比较的有界 LRU：每个调度器最多 4096 个样板，最多 8 个解析器，每个解析器最多
16384 个提供器。淘汰只会重新枚举或解析，不代表能力不支持，也不会缓存旧任务上下文。
提供器集合变更时，已移除对象随缓存失效释放。

## 验证边界

`ProviderCacheLifecycleTest` 验证 64 个提供器跨 1000 tick 只枚举一次、解析 64 次（含失败
缓存），并覆盖同 tick 变更、跨网络、临时拒收恢复、容量实时变化、LRU 淘汰和旧解析器行为。
`BatchProviderResolutionCacheTest` 继续覆盖按身份隔离、动态退出缓存及原生能力优先。
配套 LT 开发源集的 `ProviderCacheGameTests` 检查实际 Mixin 转换后的节点/全局提供器刷新。

上述计数只衡量重复枚举与解析，不表示每次完整派发都是常数时间。批量执行仍可能遍历候选
读取机器的实时容量；没有按服务器 TPS 或任意整合包负载承诺性能倍数。
