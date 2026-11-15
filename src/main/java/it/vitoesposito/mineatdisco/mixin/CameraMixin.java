package it.vitoesposito.mineatdisco.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Punches the camera in on the pulse. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	private static final float PUNCH = 0.05f;

	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float mineatdisco$punch(float fov) {
		return fov * (1 - PUNCH * MineAtDisco.pulse());
	}
}
