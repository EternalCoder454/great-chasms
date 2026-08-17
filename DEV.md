# Great Chasms: developer notes

Design and build notes. For playing the mod see [README.md](README.md); for the review findings,
open issues and the traps that are easy to reintroduce, see [REVIEW.md](REVIEW.md).

## 🛠️ Building

Requires **JDK 25**.

```
gradlew build
```

On Windows, if Gradle fails with `Unable to establish loopback connection`, your `TEMP` is resolving
to an 8.3 short path (`C:\Users\ZACHAR~1\...`), which the AF_UNIX socket Gradle uses will not accept.
Point both variables at a short plain path before invoking Gradle:

```bash
$env:TMP="C:\gtmp"; $env:TEMP="C:\gtmp"
```

Output lands in `build/libs/greatchasms-1.0.0.jar`.

Cloth Config is a `compileOnly` dependency. Every reference to it sits behind a mod-presence check
and lives in a single class, so the JVM never loads it unless the config screen is opened. That is
what keeps it genuinely optional rather than merely declared optional.

## How generation works

A chasm is defined implicitly as a **level set of a global noise field**, not walked as a path.

A `WorldCarver` cannot do this job. It may only reach `getRange()` chunks from its origin, and
raising that makes every chunk iterate `(2r+1)²` candidate origins, which becomes unusable long
before the reach is measured in thousands of blocks.

Instead each column asks a pure function "how far am I from the nearest chasm centreline, and how
wide is the chasm there". No chunk needs to know about its neighbours, which is what makes it safe
under C2ME's parallel generation, and what lets `/greatchasms locate` search **without generating or
loading a single chunk**.

### Distance in blocks

Distance is estimated as `|n| / |grad n|`, which converts an opaque noise value into a figure
measured in blocks, so widths can be configured as real block counts rather than magic thresholds.
The gradient is analytic rather than a finite difference: exact, and five times cheaper than the
central difference it replaced.

That estimate has two failure modes, and the fix for one causes the other. See REVIEW.md §3.2 before
touching `gradientFloor`.

### Terracing

A plain taper is a cone, and a cone reads as a smooth funnel however rough you make its surface,
because the width changes by the same amount every block of height. Real cliffs hold their width for
a stretch and then step.

The profile is quantised into benches with vertical risers, phase-offset per column from its own
noise so cliff lines break up instead of forming concentric rings.

### Stage ordering

```
NOISE -> SURFACE -> CARVERS -> FEATURES -> INITIALIZE_LIGHT -> LIGHT -> SPAWN -> FULL
```

- **Main carve** at `applyCarvers` TAIL: after bedrock is placed so it can be removed, before
  features so nothing decorates blocks about to be deleted.
- **Cleanup** at `applyBiomeDecoration` TAIL, covering the chunk and its eight neighbours. Features
  may write outside their own chunk and decoration order is not fixed, so a chunk cleaned at the end
  of its own decoration can still be written into afterwards.
- **Structure suppression** at `createStructures` HEAD. That runs before terrain exists, which is
  fine: the field is a pure function of the seed and is answerable before a block is placed.

`NoiseBasedChunkGenerator` is the mixin target rather than `ChunkGenerator`, because the latter
declares `applyCarvers` abstract. It is also correct in practice, since Tectonic and Regions
Unexplored ship noise settings rather than replacing the generator.

### The carve must stay idempotent

A chunk is carved up to nine times. **Nothing in the carve may derive its shape from a value the
carve itself changes.**

This has broken twice. The vertical profile once normalised by the column's own surface height, and
the ocean bias once read `OCEAN_FLOOR_WG`; both are re-primed by the carve, so each pass measured
against what the previous pass had just done. The visible result was thin vertical blades and radial
streaks, which look exactly like an LOD artifact and were misdiagnosed as one.

The profile is therefore anchored to a fixed height, and the sea floor comes from
`ChunkGenerator.getBaseHeight`, a pure function of the noise. **`chunk.getHeight` appears nowhere in
`ChasmCarver`**, and should not be reintroduced.

### Water

Ocean water is not painted in. The sea is already an infinite source and the fluid system will
cascade it down the walls on its own; generation simply fires no block updates. Exposed fluid at the
rim is marked for post-processing and given an explicit fluid tick to start it falling. Painted water
would have no source and would evaporate on the first tick.

## Debugging

`/greatchasms info` prints distance to centreline, half width, ocean factor, region gate and taper at
the player. **Use it before tuning anything.** Several rounds of this mod's development were lost to
reading screenshots instead.

A one-shot log line, `Carved the first chasm of this session at chunk X, Z`, distinguishes "no chasms
nearby" from "the mod silently disabled itself", which is a failure mode that has happened.
