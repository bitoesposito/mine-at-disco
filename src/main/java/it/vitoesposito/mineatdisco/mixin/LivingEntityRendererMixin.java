package it.vitoesposito.mineatdisco.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes every mob and player dance like a plushie, all in sync: one hop every two beats that lands on beats 1 and 3,
 * squashing on the landing and stretching at the top, while the hips sway once per bar. Every move follows a cosine,
 * so nothing snaps, and grows with the loudness of the song.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
			+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
	/** Height at the top of the hop over normal height. On landing the mob is as much shorter. */
	private static final float STRETCH = 1.19f;
	/** Share of its height a mob jumps. */
	private static final float HOP = 0.1f;
	private static final float NOD_DEGREES = 7.5f;
	private static final float LEAN_DEGREES = 6;
	private static final float TWIST_DEGREES = 4.5f;

	/** 1 when landing on beats 1 and 3, -1 at the top of the hop on beats 2 and 4. */
	private static float bounce(double beat) {
		return (float) Math.cos(Math.PI * beat);
	}

	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"))
	private void mineatdisco$nod(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo info) {
		// Legs and arm swings (including the first-person hand) follow the ramp, see EntityRendererMixin.
		state.walkAnimationPos -= MineAtDisco.lagTicks() * state.walkAnimationSpeed;
		if (state instanceof ArmedEntityRenderState armed && armed.swingAnimation > 0) {
			armed.swingAnimation = Mth.clamp(entity.getSwingAnimation(partialTick - MineAtDisco.lagTicks()), 0.01f, 0.99f);
		}
		double beat = MineAtDisco.beat();
		if (beat >= 0) {
			state.xRot += NOD_DEGREES * MineAtDisco.drive() * (0.5f + 0.5f * bounce(beat));
		}
	}

	// Right after the renderer saves the pose, the origin is still at the mob's feet, so it squashes against the ground.
	@Inject(
			method = SUBMIT,
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
	private void mineatdisco$squash(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
			CameraRenderState camera, CallbackInfo info) {
		double beat = MineAtDisco.beat();
		if (beat < 0) {
			return;
		}
		float bounce = bounce(beat);
		float drive = MineAtDisco.drive();
		float height = (float) Math.pow(STRETCH, -bounce * drive);
		// Width shrinks as height grows, so the mob keeps its volume like a squeezed plushie.
		float width = 1 / (float) Math.sqrt(height);
		pose.translate(0, HOP * drive * state.boundingBoxHeight * (1 - bounce) / 2, 0);
		pose.scale(width, height, width);
	}

	// After the body turns to face its way, Z points forward. The sway takes a bar: lean one way on beat 1, the other
	// on beat 3, with a twist that runs a quarter turn ahead, so the hips draw a figure eight.
	@Inject(
			method = SUBMIT,
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
					shift = At.Shift.AFTER))
	private void mineatdisco$sway(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
			CameraRenderState camera, CallbackInfo info) {
		double beat = MineAtDisco.beat();
		if (beat >= 0) {
			float drive = MineAtDisco.drive();
			pose.rotateDegrees(Axis.YP, TWIST_DEGREES * drive * (float) Math.sin(Math.PI * beat / 2));
			pose.rotateDegrees(Axis.ZP, LEAN_DEGREES * drive * (float) Math.cos(Math.PI * beat / 2));
		}
	}
}
