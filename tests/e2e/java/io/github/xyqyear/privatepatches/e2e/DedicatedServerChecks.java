package io.github.xyqyear.privatepatches.e2e;

import carpet.CarpetServer;
import carpet.patches.EntityPlayerMPFake;
import carpet.patches.FakeClientConnection;
import carpet.patches.NetHandlerPlayServerFake;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import io.github.xyqyear.privatepatches.patches.playerretention.mixin.MapItemSavedDataAccessor;
import io.github.xyqyear.privatepatches.e2e.mixin.RegistryProbe;
import io.github.xyqyear.privatepatches.patches.playerretention.PlayerRetentionPatch;
import io.github.xyqyear.privatepatches.patches.bluemap.BlueMapPatch;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.impl.networking.AbstractNetworkAddon;
import net.fabricmc.fabric.impl.networking.PacketListenerExtensions;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class DedicatedServerChecks implements ModInitializer {
    private static final String RULE = "playerRetentionMemoryLeakFix";
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final Map<String, Object> observations = new LinkedHashMap<>();
    private final List<AbstractNetworkAddon<?>> negativeAddons = new ArrayList<>();
    private final List<MapFixture> maps = new ArrayList<>();
    private final String mode = System.getProperty("privatepatches.e2e.mode", "regression");
    private final int cycles = Integer.getInteger("privatepatches.e2e.cycles", 100);
    private MinecraftServer server;
    private Step current;
    private int startedTick;
    private int baseline;
    private int initEvents;
    private int completedCycles;
    private int connectionCycles;
    private int fakeJoins;
    private int fakeDisconnects;
    private int disconnectsBeforeKill;
    private BlueMapProbe blueMap;
    private int mapSequence;
    private int normalJoins;
    private int normalDisconnects;
    private int normalRespawns;
    private int normalDimensionChanges;
    private boolean clientDone;
    private boolean finished;
    private EntityPlayerMPFake pending;
    private EntityPlayerMPFake active;
    private EntityPlayerMPFake replacement;
    private AbstractNetworkAddon<?> disabledAddon;
    private Object vaultTask;

    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.INIT.register((listener, game) -> initEvents++);
        ServerPlayConnectionEvents.JOIN.register((listener, sender, game) -> {
            if (listener.player instanceof EntityPlayerMPFake) fakeJoins++;
            if (mode.equals("client") && !(listener.player instanceof EntityPlayerMPFake)) {
                check(tracked(addon(listener.player)), "normal player must be registered while online");
                normalJoins++;
                track(listener.player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((listener, game) -> {
            if (listener.player instanceof EntityPlayerMPFake) fakeDisconnects++;
            if (mode.equals("client") && !(listener.player instanceof EntityPlayerMPFake)) {
                game.execute(() -> normalDisconnects++);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) ->
                dispatcher.register(Commands.literal("ppe2e")
                        .then(Commands.literal("done").executes(command -> { clientDone = true; return 1; }))
                        .then(Commands.literal("nether").executes(command -> {
                            var name = command.getSource().getPlayerOrException().getGameProfile().name();
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "execute as " + name + " in minecraft:the_nether run tp @s 0.5 80 0.5");
                            return 1;
                        }))
                        .then(Commands.literal("overworld").executes(command -> {
                            var name = command.getSource().getPlayerOrException().getGameProfile().name();
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "execute as " + name + " in minecraft:overworld run tp @s 0.5 5 0.5");
                            return 1;
                        }))
                        .then(Commands.literal("respawn").executes(command -> {
                            var name = command.getSource().getPlayerOrException().getGameProfile().name();
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kill " + name);
                            return 1;
                        }))));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (mode.equals("client")) {
                check(oldPlayer != newPlayer, "respawn must replace the player instance");
                check(tracked(addon(newPlayer)), "respawn prematurely ended the network session");
                normalRespawns++;
                track(newPlayer);
            }
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
            if (mode.equals("client")) {
                check(tracked(addon(player)), "dimension change ended the network session");
                normalDimensionChanges++;
                track(player);
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(game -> {
            server = game;
            baseline = trackedCount();
            once("shared_carpet_commands", () -> {
                var commands = server.getCommands().getDispatcher().getRoot();
                check(commands.getChild("carpet") != null, "Carpet command missing");
                check(commands.getChild("privatepatches") == null, "independent command still registered");
                for (var name : new String[]{RULE, "fixBlueMap"}) {
                    var rule = CarpetServer.settingsManager.getCarpetRule(name);
                    check(rule != null && rule.settingsManager() == CarpetServer.settingsManager,
                            "rule is not owned by Carpet: " + name);
                    check(rule.extraInfo().size() == 2, "rule translations missing: " + name);
                }
            });
            if (FabricLoader.getInstance().isModLoaded("bluemap")) blueMap = new BlueMapProbe();
            if (mode.equals("persistence")) {
                once("persistent_rule_loaded", () -> check(enabled(), "setDefault did not survive restart"));
                once("persistent_bluemap_rule_loaded", () -> check(BlueMapPatch.fixBlueMap, "fixBlueMap did not survive restart"));
                once("shared_carpet_config_loaded", this::checkSharedConfig);
            } else if (mode.equals("client")) {
                prepareClientSteps();
            } else if (mode.equals("bluemap")) {
                check(blueMap != null, "BlueMap integration profile must load the actual mod");
                waitFor("real_bluemap_loaded", 3600, blueMap::loaded);
                prepareConnectionSteps();
                preparePersistence();
            } else {
                prepareRegressionSteps();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(game -> tick());
    }

    private void prepareRegressionSteps() {
        once("default_disabled", () -> check(!enabled() && !BlueMapPatch.fixBlueMap, "rules must be opt-in"));
        once("negative_control", () -> {
            newMaps();
            for (int i = 0; i < 12; i++) {
                var player = spawn("PPNegative" + i);
                track(player);
                negativeAddons.add(addon(player));
                disconnect(player);
            }
            check(trackedCount() == baseline + 12, "network leak was not reproduced with rule off");
            check(mapAccess(0).privatepatches$getCarriedBy().size() == 12, "dormant map leak not reproduced");
            if (FabricLoader.getInstance().isModLoaded("lithium")) {
                check(mapAccess(1).privatepatches$getCarriedBy().size() == 12, "framed_maps path did not retain old players");
            }
            observations.put("negative_network_records", trackedCount() - baseline);
            observations.put("negative_dormant_holders", mapAccess(0).privatepatches$getCarriedBy().size());
            observations.put("negative_framed_holders", mapAccess(1).privatepatches$getCarriedBy().size());
        });
        waitFor("disabled_map_cleanup_is_inactive", 150, () -> {
            if (elapsed() < 110) return false;
            check(mapAccess(0).privatepatches$getCarriedByPlayers().size() == 12, "disabled rule changed map tracking");
            return true;
        });
        once("isolate_negative_control", () -> {
            // The negative control deliberately leaks; isolate it only after its assertions.
            negativeAddons.forEach(AbstractNetworkAddon::endSession);
            negativeAddons.clear();
            maps.forEach(map -> {
                ((MapItemSavedDataAccessor) map.data()).privatepatches$getCarriedBy().clear();
                ((MapItemSavedDataAccessor) map.data()).privatepatches$getCarriedByPlayers().clear();
            });
            check(trackedCount() == baseline, "negative control isolation failed");
            newMaps();
        });
        once("enable_protects_already_online_player", () -> {
            var player = spawn("PPBeforeEnable");
            track(player);
            setRule(true);
            disconnect(player);
            check(!tracked(addon(player)), "enabling must protect an existing live session");
            active = spawn("PPOnline");
            track(active);
        });
        once("reconnect_uses_player_identity", () -> {
            var old = spawn("PPReconnect");
            track(old);
            disconnect(old);
            replacement = spawn("PPReconnect");
            track(replacement);
            check(old != replacement && old.getUUID().equals(replacement.getUUID()), "reconnect fixture invalid");
        });
        if (FabricLoader.getInstance().isModLoaded("carpet-igny-addition")) {
            once("igny_vault_start", () -> {
                pending = spawn("PPVault");
                track(pending);
                startVault("PPVault");
            });
            waitFor("igny_vault_actual_logout", 100, () -> {
                if (!pending.isRemoved()) return false;
                check(!tracked(addon(pending)), "Igny vault logout retained addon");
                stopVault();
                pending = null;
                return true;
            });
            once("igny_vault_actual_stop_cleanup", () -> {
                var player = spawn("PPVaultStop");
                track(player);
                startVault("PPVaultStop");
                stopVault();
                check(player.isRemoved() && !tracked(addon(player)), "Igny vault stop retained player");
            });
        }
        waitFor("repeated_real_carpet_lifecycle", Math.max(500, cycles * 3), () -> {
            if (pending != null) {
                check(pending.isRemoved(), "Carpet kill did not remove player");
                check(!tracked(addon(pending)), "removed fake player's addon is retained");
                pending = null;
            }
            if (completedCycles == cycles) {
                observations.put("completed_fake_cycles", completedCycles);
                return true;
            }
            pending = spawn("PPCycle");
            track(pending);
            if (completedCycles % 2 == 0) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "player PPCycle kill");
            } else {
                disconnect(pending);
            }
            completedCycles++;
            return false;
        });
        waitFor("both_map_containers_release_old_instances", 150, () -> {
            if (removedHolders() != 0) return false;
            for (var map : maps) {
                var access = (MapItemSavedDataAccessor) map.data();
                check(access.privatepatches$getCarriedByPlayers().containsKey(active), "active holder removed");
                check(access.privatepatches$getCarriedByPlayers().containsKey(replacement), "new session removed by UUID");
                check(access.privatepatches$getCarriedBy().size() == 2, "unexpected map list size");
                check(access.privatepatches$getCarriedByPlayers().size() == 2, "unexpected map index size");
                check(map.data().colors[42] == 37, "map pixels changed");
            }
            check(trackedCount() == baseline + 2, "network registry grew with session count");
            observations.put("positive_removed_map_holders", removedHolders());
            observations.put("positive_network_records", trackedCount() - baseline);
            return true;
        });
        once("disable_stops_both_parts", () -> {
            setRule(false);
            var player = spawn("PPDisabled");
            track(player);
            disabledAddon = addon(player);
            disconnect(player);
            check(tracked(disabledAddon), "disabled rule still releases network records");
        });
        waitFor("disabled_map_record_survives_sweep", 150, () -> {
            if (elapsed() < 110) return false;
            check(removedHolders() > 0, "disabled rule still releases map records");
            setRule(true);
            return true;
        });
        waitFor("reenable_cleans_maps_with_documented_network_limit", 150, () -> {
            if (removedHolders() != 0) return false;
            check(tracked(disabledAddon), "historical network boundary changed");
            disabledAddon.endSession();
            disabledAddon.endSession();
            disabledAddon = null;
            disconnect(active);
            disconnect(replacement);
            active = null;
            replacement = null;
            return true;
        });
        waitFor("all_test_sessions_released", 150, () -> {
            if (removedHolders() != 0) return false;
            check(trackedCount() == baseline, "network references remain after the last player exits");
            for (var map : maps) {
                check(((MapItemSavedDataAccessor) map.data()).privatepatches$getCarriedBy().isEmpty(), "map list not empty");
                check(((MapItemSavedDataAccessor) map.data()).privatepatches$getCarriedByPlayers().isEmpty(), "map index not empty");
            }
            return true;
        });
        prepareConnectionSteps();
        preparePersistence();
    }

    private void preparePersistence() {
        once("set_default", () -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "carpet setDefault " + RULE + " true");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "carpet setDefault fixBlueMap true");
            check(enabled(), "setDefault failed");
            check(BlueMapPatch.fixBlueMap, "fixBlueMap setDefault failed");
        });
        once("shared_carpet_config_written", this::checkSharedConfig);
    }

    private void checkSharedConfig() {
        var root = server.getWorldPath(LevelResource.ROOT);
        try {
            var lines = Files.readAllLines(root.resolve("carpet.conf"));
            check(lines.contains(RULE + " true") && lines.contains("fixBlueMap true"), "rules not saved to carpet.conf");
            check(lines.contains("language zh_cn"), "existing Carpet setting was lost");
            check(!Files.exists(root.resolve("privatepatches.conf")), "independent config was created");
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot read shared Carpet configuration", failure);
        }
    }

    private void prepareConnectionSteps() {
        once("bluemap_default_disabled", () -> check(!BlueMapPatch.fixBlueMap, "fixBlueMap must be opt-in"));
        for (boolean memory : new boolean[]{false, true}) {
            for (boolean events : new boolean[]{false, true}) {
                String combination = "memory_" + memory + "_events_" + events;
                once("connection_rules_" + combination, () -> {
                    setRule(memory);
                    setBlueMapRule(events);
                    newMaps();
                    var player = spawn("PPEvents");
                    track(player);
                    expectBlueMap(player, 1);
                    int before = fakeDisconnects;
                    disconnect(player);
                    check(fakeDisconnects == before + (events ? 1 : 0), "incorrect DISCONNECT count: " + combination);
                    check(tracked(addon(player)) == !(memory || events), "incorrect session cleanup: " + combination);
                    expectBlueMap(player, events ? 0 : 1);
                    check(removedHolders() > 0, "map fixture did not retain the removed player");
                    // Isolate deliberately retained records only after checking both negative controls.
                    if (!events && blueMap != null) blueMap.isolateNegativeControl(server, player);
                    addon(player).endSession();
                });
                waitFor("connection_map_independence_" + combination, 150, () -> {
                    if (elapsed() < 110) return false;
                    check((removedHolders() == 0) == memory, "fixBlueMap changed map cleanup ownership");
                    return true;
                });
            }
        }
        once("connection_toggle_existing_player", () -> {
            setRule(false);
            setBlueMapRule(false);
            var player = spawn("PPToggle");
            setBlueMapRule(true);
            int before = fakeDisconnects;
            disconnect(player);
            check(fakeDisconnects == before + 1, "enabling must cover already-online players");
            expectBlueMap(player, 0);
            var second = spawn("PPToggle");
            setBlueMapRule(false);
            before = fakeDisconnects;
            disconnect(second);
            check(fakeDisconnects == before && tracked(addon(second)), "disabling must stop notification and cleanup");
            expectBlueMap(second, 1);
            if (blueMap != null) blueMap.isolateNegativeControl(server, second);
            addon(second).endSession();
        });
        once("connection_reconnect_and_duplicate_disconnect", () -> {
            setBlueMapRule(true);
            var old = spawn("PPEventsAgain");
            int before = fakeDisconnects;
            disconnect(old);
            check(fakeDisconnects == before + 1, "first disconnect event missing");
            var current = spawn("PPEventsAgain");
            check(old.getUUID().equals(current.getUUID()) && old != current, "reconnect fixture invalid");
            addon(old).handleDisconnect();
            addon(old).handleDisconnect();
            check(fakeDisconnects == before + 1, "old session emitted duplicate disconnect");
            expectBlueMap(current, 1);
            check(tracked(addon(current)), "old disconnect ended the new session");
            disconnect(current);
            expectBlueMap(current, 0);
        });
        if (FabricLoader.getInstance().isModLoaded("carpet-igny-addition")) {
            once("connection_igny_start", () -> {
                pending = spawn("PPEventVault");
                disconnectsBeforeKill = fakeDisconnects;
                startVault("PPEventVault");
            });
            waitFor("connection_igny_logout", 100, () -> {
                if (!pending.isRemoved()) return false;
                check(fakeDisconnects == disconnectsBeforeKill + 1, "Igny must emit one disconnect");
                check(!tracked(addon(pending)), "Igny retained the network session");
                stopVault();
                pending = null;
                return true;
            });
            once("connection_igny_stop", () -> {
                var player = spawn("PPEventStop");
                int before = fakeDisconnects;
                startVault("PPEventStop");
                stopVault();
                check(player.isRemoved() && fakeDisconnects == before + 1, "Igny stop must emit one disconnect");
                check(!tracked(addon(player)), "Igny stop retained network session");
            });
        }
        waitFor("connection_repeated_lifecycle", Math.max(500, cycles * 3), () -> {
            if (pending != null) {
                check(pending.isRemoved(), "Carpet kill did not remove player");
                check(fakeDisconnects == disconnectsBeforeKill + 1, "kill must emit exactly one disconnect");
                check(!tracked(addon(pending)), "disconnected addon retained");
                expectBlueMap(pending, 0);
                pending = null;
            }
            if (connectionCycles == cycles) {
                observations.put("completed_connection_cycles", connectionCycles);
                observations.put("fake_join_events", fakeJoins);
                observations.put("fake_disconnect_events", fakeDisconnects);
                if (blueMap != null) observations.put("bluemap_version", FabricLoader.getInstance()
                        .getModContainer("bluemap").orElseThrow().getMetadata().getVersion().getFriendlyString());
                return true;
            }
            pending = spawn("PPEventCycle");
            expectBlueMap(pending, 1);
            disconnectsBeforeKill = fakeDisconnects;
            if (connectionCycles % 2 == 0) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "player PPEventCycle kill");
            } else {
                disconnect(pending);
            }
            connectionCycles++;
            return false;
        });
        once("connection_all_sessions_released", () -> {
            check(trackedCount() == baseline, "connection test retained sessions");
            if (blueMap != null) {
                blueMap.expectEmpty();
                observations.put("bluemap_remaining_players", 0);
            }
        });
    }

    private void setBlueMapRule(boolean value) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "carpet fixBlueMap " + value);
        check(BlueMapPatch.fixBlueMap == value, "Carpet command failed to set fixBlueMap");
    }

    private void expectBlueMap(ServerPlayer player, int count) {
        if (blueMap != null) blueMap.expect(player.getUUID(), count);
    }

    private void prepareClientSteps() {
        if (blueMap != null) waitFor("real_bluemap_loaded", 3600, blueMap::loaded);
        once("client_test_setup", () -> { setRule(true); setBlueMapRule(true); newMaps(); writeReady(); });
        waitFor("ordinary_network_connect_disconnect", 3600, () -> {
            for (var player : server.getPlayerList().getPlayers()) expectBlueMap(player, 1);
            if (!clientDone || normalJoins < 3 || normalDisconnects != normalJoins
                    || !server.getPlayerList().getPlayers().isEmpty()) return false;
            check(trackedCount() == baseline, "ordinary disconnected addon retained");
            check(normalJoins == 3 && normalDisconnects == 3, "ordinary player events duplicated");
            if (blueMap != null) {
                blueMap.expectEmpty();
                observations.put("bluemap_remaining_players", 0);
                observations.put("bluemap_version", FabricLoader.getInstance().getModContainer("bluemap")
                        .orElseThrow().getMetadata().getVersion().getFriendlyString());
            }
            observations.put("normal_player_joins", normalJoins);
            observations.put("normal_player_disconnects", normalDisconnects);
            check(normalRespawns >= 1, "no ordinary respawn was tested");
            check(normalDimensionChanges >= 2, "dimension changes were not tested");
            observations.put("normal_player_respawns", normalRespawns);
            observations.put("normal_player_dimension_changes", normalDimensionChanges);
            return true;
        });
        waitFor("ordinary_player_map_references_released", 150, () -> removedHolders() == 0);
    }

    private EntityPlayerMPFake spawn(String name) {
        var profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
        var player = EntityPlayerMPFake.respawnFake(server, server.overworld(), profile, ClientInformation.createDefault());
        int before = initEvents;
        int joinsBefore = fakeJoins;
        server.getPlayerList().placeNewPlayer(new FakeClientConnection(PacketFlow.SERVERBOUND), player,
                new CommonListenerCookie(profile, 0, player.clientInformation(), false));
        player.snapTo(0.5, 81, 0.5, 0, 0);
        player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        player.getAbilities().flying = true;
        check(player.connection instanceof NetHandlerPlayServerFake, "real Carpet listener was not installed");
        check(initEvents == before + 1, "Fabric INIT behavior changed");
        check(fakeJoins == joinsBefore + 1, "fake player must emit exactly one JOIN");
        check(tracked(addon(player)), "online fake addon must retain its original initialization");
        return player;
    }

    private void startVault(String name) {
        try {
            var type = Class.forName("com.liuyue.igny.task.vault.VaultTask");
            vaultTask = type.getMethod("getOrCreate", net.minecraft.commands.CommandSourceStack.class,
                    String.class, int.class, int.class, int.class)
                    .invoke(null, server.createCommandSourceStack(), name, 1, 2, 1000);
            type.getMethod("start").invoke(vaultTask);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot exercise installed Igny vault task", failure);
        }
    }

    private void stopVault() {
        try {
            vaultTask.getClass().getMethod("stop").invoke(vaultTask);
            vaultTask = null;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot stop installed Igny vault task", failure);
        }
    }

    private void disconnect(ServerPlayer player) {
        player.connection.onDisconnect(new DisconnectionDetails(Component.literal("privatepatches regression")));
        check(player.isRemoved(), "disconnect did not remove player");
    }

    private void newMaps() {
        maps.clear();
        for (int i = 0; i < 2; i++) {
            var id = new MapId(900000 + mapSequence++);
            var data = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
            data.colors[42] = 37;
            server.getDataStorage().set(MapItemSavedData.type(id), data);
            var stack = new ItemStack(Items.FILLED_MAP);
            stack.set(DataComponents.MAP_ID, id);
            var frame = new ItemFrame(server.overworld(), new BlockPos(0, 80, 0), Direction.NORTH);
            frame.setItem(stack);
            maps.add(new MapFixture(data, stack, frame));
        }
    }

    private void track(ServerPlayer player) {
        maps.get(0).data().getHoldingPlayer(player);
        var framed = maps.get(1);
        framed.data().tickCarriedBy(player, framed.stack(), framed.frame());
    }

    private MapItemSavedDataAccessor mapAccess(int index) {
        return (MapItemSavedDataAccessor) maps.get(index).data();
    }

    private int removedHolders() {
        int count = 0;
        for (var map : maps) {
            var access = (MapItemSavedDataAccessor) map.data();
            count += (int) access.privatepatches$getCarriedBy().stream().filter(h -> h.player.isRemoved()).count();
            count += (int) access.privatepatches$getCarriedByPlayers().keySet().stream().filter(p -> p.isRemoved()).count();
        }
        return count;
    }

    private static AbstractNetworkAddon<?> addon(ServerPlayer player) {
        return ((PacketListenerExtensions) player.connection).getAddon();
    }

    private static boolean tracked(AbstractNetworkAddon<?> addon) {
        var probe = (RegistryProbe) (Object) ServerNetworkingImpl.PLAY;
        var lock = probe.privatepatches$getLock().readLock();
        lock.lock();
        try { return probe.privatepatches$getTrackedAddons().contains(addon); }
        finally { lock.unlock(); }
    }

    private static int trackedCount() {
        var probe = (RegistryProbe) (Object) ServerNetworkingImpl.PLAY;
        var lock = probe.privatepatches$getLock().readLock();
        lock.lock();
        try { return probe.privatepatches$getTrackedAddons().size(); }
        finally { lock.unlock(); }
    }

    private void setRule(boolean value) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "carpet " + RULE + " " + value);
        check(enabled() == value, "Carpet command failed to set rule");
    }

    private static boolean enabled() { return PlayerRetentionPatch.playerRetentionMemoryLeakFix; }
    private int elapsed() { return server.getTickCount() - startedTick; }
    private void once(String name, Runnable action) { steps.add(new Step(name, 1, action, () -> true)); }
    private void waitFor(String name, int ticks, BooleanSupplier condition) { steps.add(new Step(name, ticks, () -> {}, condition)); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private void tick() {
        if (server == null || finished) return;
        try {
            if (current == null) {
                if (steps.isEmpty()) { finish(null); return; }
                current = steps.removeFirst();
                startedTick = server.getTickCount();
                current.start().run();
            }
            if (current.complete().getAsBoolean()) {
                results.add(Map.of("name", current.name(), "passed", true, "ticks", elapsed()));
                System.out.println("PRIVATEPATCHES_E2E_PASS " + current.name());
                current = null;
            } else if (elapsed() > current.timeout()) {
                throw new AssertionError("timed out: " + current.name());
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(failure);
        }
    }

    private void writeReady() {
        try {
            Files.writeString(Path.of("e2e-ready.json"), "{\"port\":" + server.getPort() + "}");
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void finish(Throwable failure) {
        finished = true;
        if (failure != null) {
            var trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            results.add(Map.of("name", current == null ? "server" : current.name(), "passed", false, "error", trace.toString()));
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("passed", failure == null && !results.isEmpty());
        report.put("mode", mode);
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("minecraft", FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString());
        report.put("cycles", completedCycles);
        report.put("checks", results);
        report.put("observations", observations);
        try {
            var path = Path.of(System.getProperty("privatepatches.e2e.report", "e2e-report.json"));
            var temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { throw new IllegalStateException(error); }
        finally { server.halt(false); }
    }

    private record Step(String name, int timeout, Runnable start, BooleanSupplier complete) {}
    private record MapFixture(MapItemSavedData data, ItemStack stack, ItemFrame frame) {}
}
