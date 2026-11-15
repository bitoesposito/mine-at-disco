package it.vitoesposito.mineatdisco.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingParticlesLeavesBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Opens the vanilla falling leaf particle, so each tree sheds its own kind of leaf. */
@Mixin(FallingParticlesLeavesBlock.class)
public interface FallingLeavesInvoker {
	@Invoker("spawnFallingLeavesParticle")
	void mineatdisco$shed(Level level, BlockPos pos, RandomSource random);
}
