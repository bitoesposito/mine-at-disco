package it.vitoesposito.mineatdisco.mixin;

import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bends every entity's animation clock with the ramp, so idle moves, wings and spinning items slow down after a
 * block breaks and then catch up. Only the drawing moves: positions and hitboxes stay where the game has them.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
			at = @At("TAIL"))
	private void mineatdisco$ramp(Entity entity, EntityRenderState state, float partialTick, CallbackInfo info) {
		state.ageInTicks -= MineAtDisco.lagTicks();
	}
}
