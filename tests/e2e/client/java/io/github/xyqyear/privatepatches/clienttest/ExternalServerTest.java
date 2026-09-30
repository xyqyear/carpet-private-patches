package io.github.xyqyear.privatepatches.clienttest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ExternalServerTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (FabricLoader.getInstance().isModLoaded("privatepatches")) {
            throw new AssertionError("The production patch must not be installed on the test client");
        }
        String address = System.getProperty("privatepatches.e2e.address");
        for (int i = 0; i < 3; i++) {
            context.setScreen(TitleScreen::new);
            context.runOnClient(client -> ConnectScreen.startConnecting(client.gui.screen(), client,
                    ServerAddress.parseString(address), new ServerData("Private Patches E2E", address, ServerData.Type.OTHER),
                    false, null));
            context.waitFor(client -> client.level != null && client.player != null
                    && client.getConnection() != null && client.gui.screen() == null, 6000);
            context.waitTicks(100);
            if (i == 0) {
                context.runOnClient(client -> client.getConnection().sendCommand("ppe2e nether"));
                context.waitFor(client -> client.level != null && client.level.dimension().equals(Level.NETHER)
                        && client.gui.screen() == null, 6000);
                context.runOnClient(client -> client.getConnection().sendCommand("ppe2e overworld"));
                context.waitFor(client -> client.level != null && client.level.dimension().equals(Level.OVERWORLD)
                        && client.gui.screen() == null, 6000);
                context.waitTicks(20);
                context.runOnClient(client -> client.getConnection().sendCommand("ppe2e respawn"));
                context.waitFor(client -> client.player != null && client.player.isDeadOrDying(), 6000);
                context.runOnClient(client -> client.player.respawn());
                context.waitFor(client -> client.player != null && !client.player.isDeadOrDying()
                        && client.gui.screen() == null, 6000);
            }
            if (i == 2) {
                context.runOnClient(client -> client.getConnection().sendCommand("ppe2e done"));
                context.waitTicks(100);
            }
            context.runOnClient(client -> {
                client.level.disconnect(Component.literal("Private Patches client regression"));
                client.disconnectWithSavingScreen();
            });
            context.waitFor(client -> client.level == null && client.player == null, 6000);
            context.waitTicks(100);
            context.setScreen(TitleScreen::new);
        }
        try {
            Files.writeString(Path.of(System.getProperty("privatepatches.e2e.clientReport")),
                    "{\"passed\":true,\"connections\":3,\"dimension_changes\":2,\"respawns\":1,\"patch_installed_on_client\":false}\n");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
