package it.vitoesposito.mineatdisco.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Aligns block breaking to the song's beat: folds the 5-tick cooldown between blocks into the block's duration,
 * drives the crack stages with the ramped visual beat, and finishes breaking on the beat.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	private BlockPos destroyBlockPos;
	@Shadow
	private float destroyProgress;
	@Shadow
	private float destroyTicks;
	@Shadow
	private int destroyDelay;
	@Shadow
	private Direction destroyDirection;
	@Shadow
	private boolean isDestroying;
	@Shadow
	private ItemStack destroyingItem;

	// Without the cooldown, the server's durability update for the last block lands while the next one is being mined,
	// and vanilla would start that block over. Keep mining it with the worn tool instead.
	@ModifyReturnValue(method = "sameDestroyTarget", at = @At("RETURN"))
	private boolean mineatdisco$wornTool(boolean same, @Local(argsOnly = true) BlockPos pos) {
		if (same || !MineAtDisco.syncMining() || minecraft.player == null || !pos.equals(destroyBlockPos)) {
			return same;
		}
		ItemStack held = minecraft.player.getMainHandItem();
		if (!ItemStack.isSameItem(held, destroyingItem)) {
			return false;
		}
		destroyingItem = held;
		return true;
	}

	@Inject(method = "startDestroyBlock", at = @At("TAIL"))
	private void mineatdisco$start(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
		if (isDestroying) {
			MineAtDisco.startMining(destroyBlockPos, destroyDirection);
		}
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"))
	private void mineatdisco$noCooldown(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
		if (MineAtDisco.syncMining() && minecraft.player != null && !minecraft.player.getAbilities().instabuild) {
			destroyDelay = 0;
		}
	}

	@Inject(
			method = "continueDestroyBlock",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/tutorial/Tutorial;onDestroyBlock(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;F)V",
					ordinal = 1,
					shift = At.Shift.AFTER))
	private void mineatdisco$pace(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
		destroyProgress = MineAtDisco.paceMining(pos, side, destroyProgress, destroyTicks);
	}

	@Inject(method = "stopDestroyBlock", at = @At("HEAD"))
	private void mineatdisco$stop(CallbackInfo info) {
		MineAtDisco.stopMining();
	}

	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void mineatdisco$use(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
			CallbackInfoReturnable<InteractionResult> info) {
		MineAtDisco.noteClick();
	}

	@Inject(method = "attack", at = @At("HEAD"))
	private void mineatdisco$attack(Player player, Entity target, CallbackInfo info) {
		MineAtDisco.onAttack(target);
	}
}
