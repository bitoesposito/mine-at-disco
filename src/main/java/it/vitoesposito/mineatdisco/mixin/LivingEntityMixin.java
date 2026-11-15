package it.vitoesposito.mineatdisco.mixin;

import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Syncs combat feedback and shield parries to the song: hurt and death visuals wait for the next eighth note, shields
 * raise without vanilla's 5-tick delay while music plays, and raising the shield right before an attack lands a
 * perfect parry.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Shadow
	protected ItemStack useItem;

	@Shadow
	public abstract boolean isUsingItem();

	@Shadow
	protected abstract void blockUsingItem(ServerLevel level, LivingEntity attacker, DamageSource source, float amount,
			boolean fullBlock);

	@Inject(method = "getItemBlockingWith", at = @At("RETURN"), cancellable = true)
	private void mineatdisco$instantShield(CallbackInfoReturnable<ItemStack> info) {
		if (info.getReturnValue() == null && MineAtDisco.syncMining() && isUsingItem()
				&& useItem.has(DataComponents.BLOCKS_ATTACKS)) {
			info.setReturnValue(useItem);
		}
	}

	@Redirect(
			method = "applyItemBlocking",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/LivingEntity;blockUsingItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FZ)V"))
	private void mineatdisco$parryOrBlock(LivingEntity self, ServerLevel level, LivingEntity attacker,
			DamageSource source, float amount, boolean fullBlock) {
		if (!MineAtDisco.tryParry(self, attacker, source, amount)) {
			blockUsingItem(level, attacker, source, amount, fullBlock);
		}
	}

	@Inject(method = "applyItemBlocking", at = @At("RETURN"))
	private void mineatdisco$parryProjectile(ServerLevel level, DamageSource source, float amount,
			CallbackInfoReturnable<Float> info) {
		if (info.getReturnValueF() > 0 && source.is(DamageTypeTags.IS_PROJECTILE)) {
			MineAtDisco.tryParryProjectile((LivingEntity) (Object) this, source.getDirectEntity());
		}
	}

	@Inject(method = "handleDamageEvent", at = @At("HEAD"), cancellable = true)
	private void mineatdisco$holdDamage(DamageSource source, CallbackInfo info) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide() && MineAtDisco.holdCombat(self, () -> self.handleDamageEvent(source))) {
			info.cancel();
		}
	}

	@Inject(method = "animateHurt", at = @At("HEAD"), cancellable = true)
	private void mineatdisco$holdHurt(float yaw, CallbackInfo info) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide() && MineAtDisco.holdCombat(self, () -> self.animateHurt(yaw))) {
			info.cancel();
		}
	}

	@Inject(method = "handleEntityEvent", at = @At("HEAD"), cancellable = true)
	private void mineatdisco$holdDeath(byte id, CallbackInfo info) {
		LivingEntity self = (LivingEntity) (Object) this;
		if ((id == 3 || id == 60) && self.level().isClientSide()
				&& MineAtDisco.holdCombat(self, () -> self.handleEntityEvent(id))) {
			info.cancel();
		}
	}
}
