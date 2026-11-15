package it.vitoesposito.mineatdisco.mixin;

import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.renderer.LevelEventHandler;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moves splash and lingering potion bursts (level events 2002 and 2007) onto the song's grid. */
@Mixin(LevelEventHandler.class)
public abstract class LevelEventHandlerMixin {
	@Inject(method = "levelEvent(ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"), cancellable = true)
	private void mineatdisco$splashOnBeat(int type, BlockPos pos, int data, CallbackInfo info) {
		LevelEventHandler self = (LevelEventHandler) (Object) this;
		if ((type == 2002 || type == 2007)
				&& MineAtDisco.holdImpact(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, () -> self.levelEvent(type, pos, data))) {
			info.cancel();
		}
	}
}
