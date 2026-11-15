package it.vitoesposito.mineatdisco.mixin;

import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands the block mining and breaking effects to the mod, which plays them on the song's grid. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "addBreakingBlockEffects", at = @At("HEAD"), cancellable = true)
	private void mineatdisco$mining(BlockPos pos, Direction side, boolean sound, CallbackInfo info) {
		if (MineAtDisco.holdMining(pos, side)) {
			info.cancel();
		}
	}

	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
	private void mineatdisco$destroy(BlockPos pos, BlockState state, CallbackInfo info) {
		if (MineAtDisco.holdDestroy(pos, state)) {
			info.cancel();
		}
	}
}
