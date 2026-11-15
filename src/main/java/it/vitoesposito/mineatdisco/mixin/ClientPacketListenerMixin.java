package it.vitoesposito.mineatdisco.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Moves the burst of a thrown snowball, egg and the like onto the song's grid. Event 3 is their impact. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@WrapOperation(
			method = "handleEntityEvent",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;handleEntityEvent(B)V"))
	private void mineatdisco$impactOnBeat(Entity entity, byte id, Operation<Void> original) {
		if (id != 3 || !(entity instanceof ThrowableItemProjectile)
				|| !MineAtDisco.holdImpact(entity.getX(), entity.getY(), entity.getZ(), () -> original.call(entity, id))) {
			original.call(entity, id);
		}
	}
}
