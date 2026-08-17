package com.zachary.greatchasms.mixin;

import com.zachary.greatchasms.chasm.ChasmCarver;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carves the chasms once vanilla and every other carver has finished with the chunk.
 * <p>
 * {@code NoiseBasedChunkGenerator} rather than {@code ChunkGenerator} is targeted because the latter
 * declares {@code applyCarvers} abstract, so there is no body to inject into. This is also the right
 * target in practice: Tectonic and Regions Unexplored both ship noise settings and biomes for the
 * ordinary noise generator rather than replacing the generator itself, so hooking here picks up
 * whatever terrain they produced.
 * <p>
 * The {@code seed} parameter is the world seed, the same value
 * {@code ChunkGeneratorStructureState.getLevelSeed()} returns, so chasm shape agrees between this
 * carve and the structure suppression in {@link ChunkGeneratorMixin}.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class NoiseBasedChunkGeneratorMixin {

    @Inject(method = "applyCarvers", at = @At("TAIL"))
    private void greatchasms$carveChasms(WorldGenRegion region,
                                         long seed,
                                         RandomState randomState,
                                         BiomeManager biomeManager,
                                         StructureManager structureManager,
                                         ChunkAccess chunk,
                                         CallbackInfo ci) {
        if (!ChasmCarver.appliesTo(region.getLevel().dimension())) {
            return;
        }
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        int seaLevel = region.getSeaLevel();
        ChasmCarver.carve(chunk, seed, seaLevel, self, randomState);
    }
}
