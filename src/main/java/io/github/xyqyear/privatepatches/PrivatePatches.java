package io.github.xyqyear.privatepatches;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import carpet.utils.Translations;
import io.github.xyqyear.privatepatches.patches.bluemap.BlueMapPatch;
import io.github.xyqyear.privatepatches.patches.playerretention.PlayerRetentionPatch;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public final class PrivatePatches implements ModInitializer, CarpetExtension {
    @Override
    public void onInitialize() {
        CarpetServer.manageExtension(this);
        ServerTickEvents.END_SERVER_TICK.register(PlayerRetentionPatch::onEndTick);
    }

    @Override
    public void onGameStarted() {
        CarpetServer.settingsManager.parseSettingsClass(PlayerRetentionPatch.class);
        CarpetServer.settingsManager.parseSettingsClass(BlueMapPatch.class);
    }

    @Override
    public void onPlayerLoggedOut(ServerPlayer player) {
        try {
            BlueMapPatch.onPlayerLoggedOut(player);
        } finally {
            PlayerRetentionPatch.onPlayerLoggedOut(player);
        }
    }

    @Override
    public Map<String, String> canHasTranslations(String language) {
        return Translations.getTranslationFromResourcePath(
                "assets/privatepatches/lang/%s.json".formatted(language));
    }
}
