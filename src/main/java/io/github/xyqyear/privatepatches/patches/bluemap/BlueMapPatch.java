package io.github.xyqyear.privatepatches.patches.bluemap;

import carpet.api.settings.Rule;
import carpet.api.settings.RuleCategory;
import carpet.patches.EntityPlayerMPFake;
import carpet.patches.NetHandlerPlayServerFake;
import net.fabricmc.fabric.impl.networking.PacketListenerExtensions;
import net.minecraft.server.level.ServerPlayer;

public final class BlueMapPatch {
    @Rule(categories = RuleCategory.BUGFIX)
    public static boolean fixBlueMap = false;

    private BlueMapPatch() {}

    public static void onPlayerLoggedOut(ServerPlayer player) {
        if (fixBlueMap && player instanceof EntityPlayerMPFake
                && player.connection instanceof NetHandlerPlayServerFake) {
            ((PacketListenerExtensions) player.connection).getAddon().handleDisconnect();
        }
    }
}
