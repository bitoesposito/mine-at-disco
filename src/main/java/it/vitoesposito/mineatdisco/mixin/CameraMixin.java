package it.vitoesposito.mineatdisco.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Breathes the camera in and out with the bar. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	private static final float ZOOM = 0.08f;

	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float mineatdisco$breathe(float fov) {
		double beat = MineAtDisco.beat();
		if (beat < 0) {
			return fov;
		}
		// A cosine over two beats, closest on beats 1 and 3. A sudden zoom on a still view causes headaches,
		// so the curve has no steps and fades out when the player stops moving.
		float breath = 0.5f + 0.5f * (float) Math.cos(Math.PI * beat);
		return fov * (1 - ZOOM * MineAtDisco.motion() * breath);
	}
}
