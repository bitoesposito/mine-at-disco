package it.vitoesposito.mineatdisco.mixin;

import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Holds a fully swollen creeper until the next beat so it detonates on the downbeat, never earlier than vanilla. */
@Mixin(Creeper.class)
public abstract class CreeperMixin {
	@Shadow
	private int swell;

	@Shadow
	private int maxSwell;

	@Shadow
	private void explodeCreeper() {}

	@Redirect(
			method = "tick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Creeper;explodeCreeper()V"))
	private void mineatdisco$explodeOnBeat(Creeper self) {
		if (MineAtDisco.onBeatWindow()) {
			explodeCreeper();
		} else {
			swell = maxSwell - 1;
		}
	}
}
