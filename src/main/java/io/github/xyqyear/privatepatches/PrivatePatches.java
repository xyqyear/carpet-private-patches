package io.github.xyqyear.privatepatches;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import carpet.api.settings.SettingsManager;
import carpet.utils.Translations;
import io.github.xyqyear.privatepatches.patches.bluemap.BlueMapPatch;
import io.github.xyqyear.privatepatches.patches.playerretention.PlayerRetentionPatch;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public final class PrivatePatches implements ModInitializer, CarpetExtension {
    private final SettingsManager settings = new SettingsManager(
            FabricLoader.getInstance().getModContainer("privatepatches").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString(),
            "privatepatches", "Carpet Private Patches");

    @Override
    public void onInitialize() {
        CarpetServer.manageExtension(this);
        ServerTickEvents.END_SERVER_TICK.register(PlayerRetentionPatch::onEndTick);
    }

    @Override
    public void onGameStarted() {
        settings.parseSettingsClass(PlayerRetentionPatch.class);
        settings.parseSettingsClass(BlueMapPatch.class);
    }

    @Override
    public SettingsManager extensionSettingsManager() {
        return settings;
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
