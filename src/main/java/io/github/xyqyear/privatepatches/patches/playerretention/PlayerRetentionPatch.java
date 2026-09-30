package io.github.xyqyear.privatepatches.patches.playerretention;

import carpet.api.settings.Rule;
import carpet.api.settings.RuleCategory;
import carpet.patches.EntityPlayerMPFake;
import io.github.xyqyear.privatepatches.patches.playerretention.mixin.MapItemSavedDataAccessor;
import io.github.xyqyear.privatepatches.patches.playerretention.mixin.SavedDataStorageAccessor;
import net.fabricmc.fabric.impl.networking.PacketListenerExtensions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public final class PlayerRetentionPatch {
    @Rule(categories = RuleCategory.BUGFIX)
    public static boolean playerRetentionMemoryLeakFix = false;

    private PlayerRetentionPatch() {}

    public static void onPlayerLoggedOut(ServerPlayer player) {
        if (playerRetentionMemoryLeakFix && player instanceof EntityPlayerMPFake) {
            ((PacketListenerExtensions) player.connection).getAddon().endSession();
        }
    }

    public static void onEndTick(MinecraftServer server) {
        if (!playerRetentionMemoryLeakFix || server.getTickCount() % 100 != 0) {
            return;
        }

        for (var saved : ((SavedDataStorageAccessor) server.getDataStorage()).privatepatches$getCache().values()) {
            if (saved.orElse(null) instanceof MapItemSavedData map) {
                var access = (MapItemSavedDataAccessor) map;
                access.privatepatches$getCarriedBy().removeIf(holder -> holder.player.isRemoved());
                access.privatepatches$getCarriedByPlayers().keySet().removeIf(player -> player.isRemoved());
            }
        }
    }
}
