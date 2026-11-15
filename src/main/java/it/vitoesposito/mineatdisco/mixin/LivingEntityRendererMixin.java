package it.vitoesposito.mineatdisco.mixin;

import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes every mob nod and swell a little on the pulse. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	private static final float NOD_DEGREES = 8;
	private static final float SWELL = 0.03f;

	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"))
	private void mineatdisco$pulse(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo info) {
		float pulse = MineAtDisco.pulse();
		state.xRot += NOD_DEGREES * pulse;
		state.scale *= 1 + SWELL * pulse;
	}
}
