# Carpet Private Patches

服务器自用的 Carpet 补丁集合，每项功能使用一个 Carpet 规则开关。

当前功能：修复玩家反复退出后，Fabric 网络记录和地图跟踪记录持有玩家对象造成的内存增长；地图清理也覆盖普通玩家。

目标版本：Minecraft **26.2 / 26.3**，Java 25。仅服务端安装，依赖 Carpet 和 Fabric API，客户端无需安装。发布版本的实机测试结果见 Release 附件。

默认关闭。持久开启：

```text
/privatepatches setDefault playerRetentionMemoryLeakFix true
```

临时切换：`/privatepatches playerRetentionMemoryLeakFix true/false`。

关闭期间已经泄漏的网络记录需要重启释放。地图清理每 100 tick 执行一次。

- [问题背景、原因与修复边界](docs/patches/player-retention.md)
- [开发指南](AGENTS.md)
- [实机测试](tests/e2e/README.md)

维护于 `main`，使用 `v<mod-version>` tag 发布。
