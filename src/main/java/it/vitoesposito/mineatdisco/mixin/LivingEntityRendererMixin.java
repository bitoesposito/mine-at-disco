package it.vitoesposito.mineatdisco.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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

/**
 * Makes every mob dance: it squashes on the beat, springs up between beats, leans to one side per beat and nods.
 * Every move follows a cosine, so nothing snaps.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
			+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
	private static final float NOD_DEGREES = 25;
	private static final float LEAN_DEGREES = 16;
	/** Share of its height a mob loses on the beat and gains between beats. */
	private static final float SQUASH = 0.2f;
	/** Share of its height a mob jumps between beats. */
	private static final float HOP = 0.15f;

	/** 1 on the beat, -1 halfway to the next. */
	private static float bounce(double beat) {
		return (float) Math.cos(2 * Math.PI * beat);
	}

	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"))
	private void mineatdisco$nod(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo info) {
		double beat = MineAtDisco.beat();
		if (beat >= 0) {
			state.xRot += NOD_DEGREES * (0.5f + 0.5f * bounce(beat));
		}
	}

	// Right after the renderer saves the pose, the origin is still at the mob's feet, so it squashes against the ground.
	@Inject(
			method = SUBMIT,
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
	private void mineatdisco$bounce(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
			CameraRenderState camera, CallbackInfo info) {
		double beat = MineAtDisco.beat();
		if (beat < 0) {
			return;
		}
		float bounce = bounce(beat);
		pose.translate(0, HOP * state.boundingBoxHeight * (1 - bounce) / 2, 0);
		pose.scale(1 + SQUASH * 0.7f * bounce, 1 - SQUASH * bounce, 1 + SQUASH * 0.7f * bounce);
	}

	// After the body turns to face its way, Z points forward, so this leans the mob to its side: left on one beat, right on the next.
	@Inject(
			method = SUBMIT,
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
					shift = At.Shift.AFTER))
	private void mineatdisco$lean(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
			CameraRenderState camera, CallbackInfo info) {
		double beat = MineAtDisco.beat();
		if (beat >= 0) {
			pose.rotateDegrees(Axis.ZP, LEAN_DEGREES * (float) Math.cos(Math.PI * beat));
		}
	}
}
