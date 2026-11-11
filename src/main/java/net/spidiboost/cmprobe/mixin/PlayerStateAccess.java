package net.spidiboost.cmprobe.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerEntity.class)
public interface PlayerStateAccess {
    @Accessor("lastSprinting") boolean probe$lastSprint();
    @Accessor("lastSneaking") boolean probe$lastSneak();
    @Accessor("lastSprinting") void probe$lastSprint(boolean value);
    @Accessor("lastSneaking") void probe$lastSneak(boolean value);
    @Accessor("lastPlayerInput") void probe$lastInput(PlayerInput value);
}
