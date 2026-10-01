# 玩家退出后的内存滞留

规则：`playerRetentionMemoryLeakFix`，默认 `false`。网络记录和地图记录属于同一个功能，共用这一开关。

## 背景与现象

2026-09-30 对 `mc-vanilla` 的 heap dump 分析发现 3,496 个 Carpet 假玩家实例，其中 3,495 个已经移除。相关保留对象约占 4.77 GiB。Spark 的约 361 秒采样中出现 143 次老年代 GC，累计约 322 秒。

这是历史会话累积，不是数千名玩家同时在线的正常内存需求。现场还有 15 个已移除的普通 `ServerPlayer`，其中一个已确认由地图缓存持有。因此，地图问题也影响反复登录、退出的普通玩家。

现场版本：Minecraft 26.2、Carpet 26.2+v260616、Fabric API 0.155.2+26.2、Lithium 0.25.2+mc26.2、TIS Addition 1.82.3、Igny Addition 1.18.0。数字描述这次现场快照，不代表所有服务器的内存消耗。

## 两条保留路径

### Fabric 网络注册记录

```text
ServerNetworkingImpl.PLAY
  -> GlobalReceiverRegistry.trackedAddons
  -> ServerPlayNetworkAddon
  -> NetHandlerPlayServerFake
  -> EntityPlayerMPFake
```

Carpet 假玩家与 Igny 宝库任务直接调用玩家监听器的 `onDisconnect()`。Fabric 的清理入口挂在底层 `Connection` 的断开路径，直接调用绕过了这个入口。玩家从世界中移除了，静态注册表仍持有网络 addon。

Fabric 的历史修复提供 `UntrackedPacketListener`，但 Carpet 假玩家没有实现它。直接加入这个标记会跳过部分初始化，也不能作为运行中可切换的 Carpet 规则。

### 地图跟踪记录

```text
SavedDataStorage.cache
  -> MapItemSavedData.carriedBy / carriedByPlayers
  -> HoldingPlayer.player
```

原版主要在地图更新时清理旧玩家，地图不再更新就可能保留旧引用。Lithium 的 `entity.framed_maps` 优化只处理当前玩家的记录，跳过了其他已退出玩家的清理，因此展示框地图附近频繁的玩家更替会放大问题。

进度数据是被这些玩家对象连带保留的对象之一，不能据此把进度系统判为泄漏起点。

## 修复

规则开启时执行两项操作：

1. 在 Carpet 自带的 `CarpetExtension.onPlayerLoggedOut` 回调中，对 Carpet 假玩家调用 Fabric addon 的 `endSession()`。它使用注册表自己的锁删除记录，保留原有登录初始化、INIT 事件和在线频道注册。
2. 每 100 个服务端 tick，在主线程 tick 结束时遍历已加载地图，分别删除两个容器中指向 `isRemoved()` 玩家实例的条目。

Carpet 的退出回调位于监听器 `onDisconnect()` 的入口，所以也覆盖 Igny 直接调用监听器的方式。只调用 `endSession()`，不额外触发其他 Mod 的 Fabric DISCONNECT 订阅者；补丁目标是本次确认的强引用路径。

独立的 [`fixBlueMap`](bluemap.md) 规则可以补齐 Fabric DISCONNECT 通知，并由 Fabric 顺带注销网络会话。两个规则都开启时，退出通知仍只发送一次；本规则继续独立控制地图引用清理。

地图清理按具体对象和移除状态判断，不按名字或 UUID 删除，避免误伤同账号的新会话。它也清理普通玩家的旧引用。不加载额外区块或地图文件，不修改地图像素、标记、玩家存档或进度。

生产实现只有一个功能类、两个地图 Accessor 与一个 Carpet 扩展入口。Mixin 专用子包与普通功能类分开，避免 Mixin 的类加载限制。没有额外的玩家、连接或世界缓存。

## 开关与边界

```text
/privatepatches playerRetentionMemoryLeakFix true
/privatepatches setDefault playerRetentionMemoryLeakFix true
/privatepatches playerRetentionMemoryLeakFix false
```

`setDefault` 写入世界目录的 `privatepatches.conf`。

- 开启后，已经在线的假玩家在后续退出时也会释放网络记录。
- 地图清理每 100 tick 执行一次，正常 TPS 下约 5 秒；低 TPS 时更久。
- 关闭后，两项清理都停止；已经释放的无效引用不会恢复。
- 关闭期间已经退出并泄漏的假玩家不会重新触发退出回调。这些历史网络记录需要重启释放；历史地图记录可被后续扫描清理。
- 首次安装本 Mod 需要重启。建议安装后持久开启，从干净进程开始。
- 不修复其他 Mod 自己持有的引用、发包队列，或所有第三方假连接实现。

## 升级核查

调研时对比了实际安装包。检查的 Minecraft 26.3、Carpet 26.3+v260915、Fabric API 0.161.0 与 Lithium 0.25.3/0.26.2 的相关逻辑仍然存在。Igny 1.18.0 的旧 Fabric 注册表修复在实际 26.2 JAR 中是空类；TIS 和其他同名发包修复处理的是另一条路径。

本补丁需要维护的边界很小：Carpet 的退出回调、Fabric impl 包的 `PacketListenerExtensions` / `endSession()`、`SavedDataStorage.cache`、地图的两个跟踪字段。Fabric impl 包不是稳定公共契约；每次升级都要复查并运行测试。

26.2 与 26.3 的相关 Carpet/Fabric 类字节码相同，地图访问成员描述符一致。发布兼容性仍以同一个 JAR 在两版真实服务端上的测试为准，而不是只修改 metadata 或只看编译结果。

## 验证与发布

测试直接启动独立的普通 Minecraft DedicatedServer，加载正式 JAR 和单独的测试 Mod。测试代码全部在 `tests/e2e`，不随正式 Mod 发布。

覆盖：关闭规则的负对照、两个引用容器、Carpet kill 与直接 disconnect、1,000 次会话、活跃玩家、同 UUID 新会话、动态切换、配置重启持久化。现场模组组合额外调用已安装 Igny 的真实宝库退出和停止清理路径；这不代表测试了宝库战利品玩法。

客户端测试启动真实游戏客户端，连接独立服务端，验证三次连接、换维度、死亡重生及退出后的清理。客户端没有安装本补丁。虚拟显示只用于无人值守运行客户端。

主要断言是强引用容器中的对象状态。在线玩家的 addon 本来就应该存在，不能要求整个注册表无条件为零。测试不把 RSS 下降或立即执行 GC 当成通过标准。测试观测器保留的临时引用也不是生产泄漏结论。

每个 Release 的 `compatibility.json` 记录实际依赖、检查结果和待测 JAR SHA-256。CI 所有必需矩阵项通过后发布同一份文件，不自动部署生产服。

## 上游参考

- [Carpet 同类问题 #1957](https://github.com/gnembon/fabric-carpet/issues/1957)
- [Fabric 假玩家跟踪修复 #3977](https://github.com/FabricMC/fabric-api/pull/3977)
- [Carpet 退出回调入口](https://github.com/gnembon/fabric-carpet/blob/v26.3/src/main/java/carpet/mixins/ServerGamePacketListenerImpl_coreMixin.java)
- [Fabric addon 会话清理](https://github.com/FabricMC/fabric-api/blob/26.3/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/impl/networking/AbstractNetworkAddon.java)
- [Lithium framed_maps 优化](https://github.com/CaffeineMC/lithium/blob/mc26.2-0.25.3/common/src/main/java/net/caffeinemc/mods/lithium/mixin/entity/framed_maps/MapItemSavedDataMixin.java)
- [Igny 旧修复的条件编译](https://github.com/liuyuexiaoyu1/Carpet-Igny-Addition/blob/017e911609abc2f8ca17be3c1e7e8e6f7e4ad5b5/src/main/java/com/liuyue/igny/mixins/compat/fapi/AbstractNetworkAddonMixin.java)
