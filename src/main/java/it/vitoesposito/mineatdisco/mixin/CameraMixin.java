package it.vitoesposito.mineatdisco.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Kicks the view a little wider on beats 1 and 3 while the player moves, like a burst of speed on the beat. It only
 * widens: a narrowing view reads as slowing down, which felt like losing speed every other beat while running.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	private static final float KICK = 0.05f;
	/** Share of the two-beat cycle the view takes to open up before the beat. The rest eases it back. */
	private static final double RISE = 0.2;

	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float mineatdisco$kick(float fov) {
		double beat = MineAtDisco.beatSteady();
		if (beat < 0) {
			return fov;
		}
		// Phase in the two-beat cycle, 0 on beats 1 and 3. Both halves are raised cosines, so the kick never steps:
		// a sudden change on a still view causes headaches, and the kick also fades out when the player stops.
		double u = beat / 2 % 1;
		double kick = u >= 1 - RISE
				? 0.5 - 0.5 * Math.cos(Math.PI * (u - 1 + RISE) / RISE)
				: 0.5 + 0.5 * Math.cos(Math.PI * u / (1 - RISE));
		// Follow the FOV Effects slider in the accessibility options, like vanilla's sprint zoom.
		float scale = Minecraft.getInstance().options.fovEffectScale().get().floatValue();
		// Sized by the kick that opens the cycle, so a hard hit pushes harder and a beat without drums barely moves.
		float hit = 0.5f + 0.5f * MineAtDisco.cyclePunch();
		return fov * (1 + KICK * scale * MineAtDisco.motion() * MineAtDisco.drive() * hit * (float) kick);
	}
}
