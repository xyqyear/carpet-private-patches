package io.github.xyqyear.privatepatches.patches.playerretention.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(MapItemSavedData.class)
public interface MapItemSavedDataAccessor {
    @Accessor("carriedBy")
    List<MapItemSavedData.HoldingPlayer> privatepatches$getCarriedBy();

    @Accessor("carriedByPlayers")
    Map<Player, MapItemSavedData.HoldingPlayer> privatepatches$getCarriedByPlayers();
}
