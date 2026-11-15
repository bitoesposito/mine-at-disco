package it.vitoesposito.mineatdisco.mixin;

import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Bends particle motion with the beat's velocity ramp. Only the step changes: velocity, gravity and age run as usual,
 * so chips hang in the air after the beat and then fly into the next one.
 */
@Mixin(Particle.class)
public abstract class ParticleMixin {
	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private double mineatdisco$x(double x) {
		return x * MineAtDisco.particleSpeed();
	}

	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private double mineatdisco$y(double y) {
		return y * MineAtDisco.particleSpeed();
	}

	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, ordinal = 2)
	private double mineatdisco$z(double z) {
		return z * MineAtDisco.particleSpeed();
	}
}
