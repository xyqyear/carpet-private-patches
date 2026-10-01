# BlueMap 假玩家退出通知

规则：`fixBlueMap`，默认 `false`。适用于 Minecraft 26.2 和 26.3，仅服务端安装；BlueMap 是可选模组。

## 问题与原因

BlueMap 通过 Fabric `ServerPlayConnectionEvents.JOIN` / `DISCONNECT` 维护在线玩家 Map 和 List。Carpet 假玩家直接调用监听器的 `onDisconnect()`，跳过底层连接的 Fabric 退出入口，BlueMap 因而可能继续显示已经退出的假玩家。

目标版本的 Fabric 已在 `PlayerList.placeNewPlayer` 中调用 `onClientReady()`，正常发送假玩家的 JOIN。重复补发 JOIN 会使 BlueMap 的 List 再追加同一个 UUID 的记录。因此本功能保留原有登录流程。

## 实现

`BlueMapPatch` 使用 Carpet 自带的 `onPlayerLoggedOut` 回调，仅处理 `EntityPlayerMPFake` 与 `NetHandlerPlayServerFake` 的组合。开启时调用现有 Fabric addon 的 `handleDisconnect()`，由 Fabric 的原子状态保证退出通知只发送一次，然后注销网络会话。

没有新增 Mixin、动态代理、玩家缓存或 BlueMap 生产依赖。该事件会通知所有 Fabric 退出订阅者，不仅是 BlueMap。普通玩家继续使用原有网络断开流程；换维度和重生不会在本功能中被视为退出。

内存功能的退出清理位于扩展入口的 `finally` 中，即使第三方退出监听器抛异常，已经开启的内存规则仍执行网络引用清理；异常不会被静默吞掉。

## 两个规则的关系

| playerRetentionMemoryLeakFix | fixBlueMap | 假玩家网络注销 | Fabric 退出通知 | 地图旧引用清理 |
|---|---|---|---|---|
| false | false | 不补齐 | 不补齐 | 关闭 |
| true | false | 补齐 | 不补齐 | 开启 |
| false | true | 补齐 | 补齐一次 | 关闭 |
| true | true | 补齐 | 补齐一次 | 开启 |

`fixBlueMap` 顺带释放网络注册记录，是 Fabric 完整退出流程的自然结果。它不清理原版地图物品的玩家引用，也不能替代整个内存功能。

```text
/carpet setDefault fixBlueMap true
/carpet fixBlueMap true
/carpet fixBlueMap false
```

配置使用世界目录下的 `carpet.conf`。从 0.2.0 或更早版本升级时，旧 `privatepatches.conf` 不会自动导入；请用 `/carpet setDefault` 按原值重新保存两项规则。

开启后覆盖后续退出，包括已经在线的假玩家。关闭后不补发退出通知。开启规则不会为已经离线的历史实例重放事件，已有残留应通过重启等方式清理。避免同时启用其他模组直接广播同类事件的补丁：Fabric 的幂等状态无法约束绕过它直接调用事件 invoker 的代码。

## 实机验证

`tests/e2e` 启动真实 DedicatedServer，加载正式补丁 JAR、Carpet、Fabric API 和独立测试 Mod。`bluemap` 依赖组合额外加载固定 SHA-512 的 BlueMap 5.28 Fabric，并等待其插件完成加载，再检查真实在线玩家 Map 和 List。测试通过反射读取可选 BlueMap 的状态，反射代码不会进入正式 JAR。

覆盖两规则四种组合、关闭时的残留负对照、开启/关闭时已有在线玩家、每次登录恰好一个 JOIN、退出事件恰好一次、旧会话重复断开不删除同 UUID 新会话、Carpet kill 与直接 disconnect 循环、配置重启持久化。现场模组组合另外验证 Igny 的实际退出和停止路径。真实客户端测试在两个规则开启时验证普通玩家的连接、重生、换维度与退出。

负对照在确认残留后才执行测试专用清理，后续正对照必须由正式补丁完成。BlueMap 测试关闭网页服务、网页生成、皮肤下载和指标上报，验证玩家跟踪链路，不测试网页绘制或地图渲染效果。

## 参考

- [Carpet #1962](https://github.com/gnembon/fabric-carpet/issues/1962)
- [Carpet PR #2142](https://github.com/gnembon/fabric-carpet/pull/2142)
- [PRY fixBlueMap 的参考实现](https://github.com/brokeyuan/Carpet-PRY-Addition/blob/5f7f9724b93011c72b6993f63b5a748363411d31/src/main/java/me/primaryuan/carpet/mixins/rule/fixBluemap/PlayerListFakePlayerEventsMixin.java)
- [Fabric 会话退出实现](https://github.com/FabricMC/fabric-api/blob/26.3/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/impl/networking/AbstractNetworkAddon.java)
- [BlueMap 在线玩家维护](https://github.com/BlueMap-Minecraft/BlueMap/blob/088123af013cb31b6fa1e27baa732792c774f914/implementations/fabric/src/main/java/de/bluecolored/bluemap/fabric/FabricMod.java)

实现根据上述调用链独立编写，没有复制 PRY 的 Mixin 或代理发送器。
