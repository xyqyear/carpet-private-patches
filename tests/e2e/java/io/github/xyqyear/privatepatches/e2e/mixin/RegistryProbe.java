package io.github.xyqyear.privatepatches.e2e.mixin;

import net.fabricmc.fabric.impl.networking.AbstractNetworkAddon;
import net.fabricmc.fabric.impl.networking.GlobalReceiverRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

@Mixin(GlobalReceiverRegistry.class)
public interface RegistryProbe {
    @Accessor("trackedAddons")
    Set<AbstractNetworkAddon<?>> privatepatches$getTrackedAddons();

    @Accessor("lock")
    ReadWriteLock privatepatches$getLock();
}
