package io.github.xyqyear.privatepatches.e2e;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class BlueMapProbe {
    private final Object mod;
    private final Map<?, ?> players;
    private final List<?> playerList;

    BlueMapProbe() {
        mod = FabricLoader.getInstance().getEntrypointContainers("main", ModInitializer.class).stream()
                .filter(entry -> entry.getProvider().getMetadata().getId().equals("bluemap"))
                .findFirst().orElseThrow().getEntrypoint();
        try {
            players = (Map<?, ?>) mod.getClass().getMethod("getOnlinePlayers").invoke(mod);
            var field = mod.getClass().getDeclaredField("onlinePlayerList");
            field.setAccessible(true);
            playerList = (List<?>) field.get(mod);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect installed BlueMap player collections", failure);
        }
    }

    boolean loaded() {
        try {
            var plugin = mod.getClass().getMethod("getPluginInstance").invoke(mod);
            return (boolean) plugin.getClass().getMethod("isLoaded").invoke(plugin);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect the installed BlueMap plugin", failure);
        }
    }

    void expect(UUID uuid, int count) {
        try {
            int matches = 0;
            synchronized (playerList) {
                for (var player : playerList) {
                    if (uuid.equals(player.getClass().getMethod("getUuid").invoke(player))) matches++;
                }
            }
            if (players.containsKey(uuid) != (count > 0) || matches != count) {
                throw new AssertionError("BlueMap tracking mismatch for " + uuid
                        + ": map=" + players.containsKey(uuid) + ", list=" + matches + ", expected=" + count);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect installed BlueMap player collections", failure);
        }
    }

    void expectEmpty() {
        if (!players.isEmpty() || !playerList.isEmpty()) {
            throw new AssertionError("BlueMap still retains online player records");
        }
    }

    void isolateNegativeControl(MinecraftServer server, ServerPlayer player) {
        try {
            mod.getClass().getMethod("onPlayerLeave", MinecraftServer.class, ServerPlayer.class)
                    .invoke(mod, server, player);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot isolate the reproduced BlueMap leak", failure);
        }
    }
}
