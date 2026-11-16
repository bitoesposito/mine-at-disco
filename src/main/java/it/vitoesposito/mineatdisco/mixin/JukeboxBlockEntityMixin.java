package it.vitoesposito.mineatdisco.mixin;

import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the mod which disc a jukebox holds before it starts playing, so a disc burned with a song plays that song. Runs
 * on the integrated server, in worlds this game runs.
 */
@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin {
	@Inject(method = {"setTheItem", "setSongItemWithoutPlaying"}, at = @At("HEAD"))
	private void mineatdisco$noteDisc(ItemStack item, CallbackInfo info) {
		JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
		MineAtDisco.jukeboxItem(self.getLevel(), self.getBlockPos(), item);
	}
}
