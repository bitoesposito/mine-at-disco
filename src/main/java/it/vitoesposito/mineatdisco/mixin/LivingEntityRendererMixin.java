package it.vitoesposito.mineatdisco.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes every mob nod and squash on the pulse. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	private static final float NOD_DEGREES = 15;
	private static final float WIDEN = 0.10f;
	private static final float FLATTEN = 0.12f;

	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"))
	private void mineatdisco$nod(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo info) {
		state.xRot += NOD_DEGREES * MineAtDisco.pulse();
	}

	// Right after the renderer saves the pose, the origin is still at the mob's feet, so it squashes against the ground.
	@Inject(
			method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
	private void mineatdisco$squash(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
			CameraRenderState camera, CallbackInfo info) {
		float pulse = MineAtDisco.pulse();
		pose.scale(1 + WIDEN * pulse, 1 - FLATTEN * pulse, 1 + WIDEN * pulse);
	}
}
