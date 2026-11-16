package it.vitoesposito.mineatdisco.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.vitoesposito.mineatdisco.MineAtDisco;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Lets a burned disc play for its song's length instead of the length of the record it was burned on. */
@Mixin(JukeboxSongPlayer.class)
public abstract class JukeboxSongPlayerMixin {
	@Shadow
	@Final
	private BlockPos blockPos;

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/JukeboxSong;hasFinished(J)Z"))
	private boolean mineatdisco$songLength(JukeboxSong song, long ticks, Operation<Boolean> original,
			@Local(argsOnly = true) LevelAccessor level) {
		return MineAtDisco.jukeboxFinished(level, blockPos, original.call(song, ticks), ticks);
	}
}
