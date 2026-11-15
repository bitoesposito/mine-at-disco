package it.vitoesposito.mineatdisco.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Swells every flat particle on the pulse: smoke, flames, leaves, dust. */
@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleMixin {
	private static final float SWELL = 0.8f;

	@ModifyExpressionValue(
			method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/SingleQuadParticle;getQuadSize(F)F"))
	private float mineatdisco$swell(float size) {
		return size * (1 + SWELL * MineAtDisco.pulse());
	}
}
