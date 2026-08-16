package com.zachary.greatchasms.mixin;

import com.zachary.greatchasms.ChasmConfig;
import com.zachary.greatchasms.chasm.ChasmCarver;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops structures from starting in a chunk a chasm runs through.
 * <p>
 * This has to happen at STRUCTURE_STARTS, which the chunk pipeline runs long before terrain exists,
 * so it cannot look at the carved world and decide. It asks the chasm field directly instead, which
 * works precisely because that field is a pure function of the seed and is therefore already
 * answerable before a single block has been placed.
 * <p>
 * Cancelling here suppresses every structure set for the chunk at once, which is what the design
 * calls for: no oil wells, no camps, nothing. Note this suppresses structure <em>starts</em>. A
 * structure whose start lies in a neighbouring chunk can still extend a piece over the rim, so the
 * check deliberately includes half a chunk diagonal of slack to widen the exclusion band.
 */
@Mixin(ChunkGenerator.class)
public class ChunkGeneratorMixin {

    /**
     * Second carve, after every mod has decorated the chunk.
     * <p>
     * The main carve runs at the tail of {@code applyCarvers}, which is early enough to delete
     * bedrock but too early to stop anything placed during the feature stage. Streams Reflowing
     * writes its rivers there, so it was filling a hole that already existed and leaving sheets of
     * water hanging in the void. Suppressing structure starts does not help, because a river is a
     * feature, not a structure.
     * <p>
     * Rather than fight each mod individually, the chasm volume is simply re-emptied once decoration
     * is finished. That covers Streams Reflowing, Oritech oil wells and anything else, present or
     * future, without knowing anything about them.
     * <p>
     * Caveat worth knowing: features are allowed to write a short way into neighbouring chunks, so a
     * feature rooted just outside a chasm chunk can still drop a few blocks over the rim after this
     * pass has run on that chunk.
     */
    @Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
    private void greatchasms$reclearAfterDecoration(WorldGenLevel level,
                                                    ChunkAccess chunk,
                                                    StructureManager structureManager,
                                                    CallbackInfo ci) {
        if (!ChasmCarver.appliesTo(level.getLevel().dimension())) {
            return;
        }
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        ChasmCarver.carve(chunk, level.getSeed(), self.getSeaLevel());
    }

    @Inject(method = "createStructures", at = @At("HEAD"), cancellable = true)
    private void greatchasms$suppressStructuresInChasms(RegistryAccess registryAccess,
                                                        ChunkGeneratorStructureState structureState,
                                                        StructureManager structureManager,
                                                        ChunkAccess chunk,
                                                        StructureTemplateManager templateManager,
                                                        ResourceKey<Level> dimension,
                                                        CallbackInfo ci) {
        if (!ChasmConfig.blockStructures() || !ChasmCarver.appliesTo(dimension)) {
            return;
        }
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        int seaLevel = self.getSeaLevel();
        // Terrain does not exist yet at this stage, so the sea floor is queried from the generator
        // rather than read off a heightmap. Same quantity, just predicted instead of measured.
        int floor = self.getBaseHeight(chunk.getPos().getMiddleBlockX(), chunk.getPos().getMiddleBlockZ(),
                Heightmap.Types.OCEAN_FLOOR_WG, chunk, structureState.randomState());
        if (ChasmCarver.intersectsChunk(structureState.getLevelSeed(), chunk.getPos(), floor, seaLevel)) {
            ci.cancel();
        }
    }
}
