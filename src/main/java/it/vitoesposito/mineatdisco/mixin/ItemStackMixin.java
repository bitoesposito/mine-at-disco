package it.vitoesposito.mineatdisco.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Turns off vanilla's chewing timer for the player while music plays, so the mod can chew on the beat instead. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@ModifyExpressionValue(
			method = "onUseTick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/component/Consumable;shouldEmitParticlesAndSounds(I)Z"))
	private boolean mineatdisco$chewOnBeat(boolean emit, @Local(argsOnly = true) LivingEntity entity) {
		return emit && !MineAtDisco.ownsChewing(entity);
	}
}
