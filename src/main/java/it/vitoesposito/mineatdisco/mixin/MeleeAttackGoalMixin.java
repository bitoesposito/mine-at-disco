package it.vitoesposito.mineatdisco.mixin;

import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds melee mob attacks until the beat, with a warning cue on the eighth note before it so the player can parry in
 * time with the song.
 */
@Mixin(MeleeAttackGoal.class)
public abstract class MeleeAttackGoalMixin {
	@Shadow
	@Final
	protected PathfinderMob mob;

	@Shadow
	protected abstract boolean canPerformAttack(LivingEntity target);

	@Inject(method = "checkAndPerformAttack", at = @At("HEAD"), cancellable = true)
	private void mineatdisco$waitBeat(LivingEntity target, CallbackInfo info) {
		if (canPerformAttack(target) && !MineAtDisco.canMobAttack(mob)) {
			info.cancel();
		}
	}
}
