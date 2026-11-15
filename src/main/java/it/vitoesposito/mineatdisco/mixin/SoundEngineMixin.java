package it.vitoesposito.mineatdisco.mixin;

import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.Camera;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the mod hold a sound back until the song's next grid line. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	@Shadow
	public abstract SoundEngine.PlayResult play(SoundInstance sound);

	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void mineatdisco$defer(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> result) {
		if (MineAtDisco.defer(sound)) {
			result.setReturnValue(SoundEngine.PlayResult.STARTED);
		}
	}

	// The game calls updateSource once per frame.
	@Inject(method = "updateSource", at = @At("HEAD"))
	private void mineatdisco$frame(Camera camera, CallbackInfo info) {
		MineAtDisco.frame(this::play);
	}
}
