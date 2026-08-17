# Great Chasms

<img src="src/main/resources/icon.png" alt="Great Chasms" width="180">

Minecraft's ravines are a few dozen blocks deep and easy to miss. Great Chasms replaces that idea
with something worth the name: rifts that run for **thousands of blocks**, open **hundreds of blocks
wide**, and drop straight past bedrock into the void. Crossing one means flying or building a bridge.

They cut through ocean floor and continent alike, narrowing as they come ashore. Because they expose
the whole stratigraphy from grass to deepslate in a single wall, they are also the fastest way to
find ore in the game.

**Minecraft 26.1.2 on NeoForge**, Java 25.

## Compatibility

Built to sit alongside **Tectonic**, **Regions Unexplored**, **Streams Reflowing**, **C2ME** and
**Lithostitched**. All optional, none required.

The carve hooks the ordinary noise generator rather than replacing it, so it composes with whatever
terrain those mods produce instead of competing with them. Tectonic's config is also where vanilla
ravines get switched off, which is the intended pairing: small ravines removed there, real chasms
added here.

## How it works

A chasm is defined implicitly as a **level set of a global noise field**, not walked as a path. A
`WorldCarver` cannot do this job: it may only reach `getRange()` chunks from its origin, and raising
that makes every chunk iterate `(2r+1)²` candidate origins, which becomes unusable long before the
reach is measured in thousands of blocks.

Instead each column asks a pure function "how far am I from the nearest chasm centreline, and how
wide is the chasm there". No chunk ever needs to know about its neighbours, which is what makes it
safe under C2ME's parallel generation, and what lets `/greatchasms locate` find a chasm **without
generating or loading a single chunk**.

Distance is estimated as `|n| / |grad n|`, which turns an opaque noise value into a figure measured
in blocks, so widths can be configured as real block counts rather than magic thresholds. The
gradient is analytic rather than a finite difference, which is both exact and five times cheaper.

**Walls are terraced.** A plain taper is a cone, and a cone reads as a smooth funnel however rough
you make its surface, because the width changes by the same amount every block of height. Real cliffs
hold their width for a stretch and then step. The profile is quantised into benches with vertical
risers, phase-offset per column so the cliff lines break up instead of forming concentric rings.

Carving runs at the tail of `applyCarvers`: after the noise and surface passes so bedrock can be
removed, and before features so nothing decorates blocks that are about to be deleted. A second pass
after decoration re-empties the volume, including the eight neighbouring chunks, because features may
write outside their own chunk and decoration order is not fixed.

The carve is **idempotent**, which it has to be, since a chunk can be carved up to nine times.
Nothing in it may derive its shape from a value the carve itself changes, so the vertical profile is
anchored to a fixed height and the sea floor comes from the generator's prediction rather than from a
heightmap the carve re-primes.

Ocean water is not painted in. The sea is already an infinite source and the fluid system will
cascade it down the walls on its own; generation simply fires no block updates, so exposed fluid is
marked and given an explicit tick to start it falling.

## Commands

| Command | Effect |
|---|---|
| `/greatchasms locate` | Nearest chasm, found analytically without loading chunks. Prints distance, width and a compass bearing, with a clickable **[Teleport]** that drops you 90 blocks above the rim |
| `/greatchasms info` | Field values at your feet: distance to centreline, width, ocean factor, region gate and taper |

## Configuration

Install **[Cloth Config](https://modrinth.com/mod/cloth-config)** for an in-game screen, reachable
from the Config button in the mod list or Catalogue. Entirely optional: without it the mod behaves
identically and you edit the file instead. The screen separates the settings that take effect
immediately from the ones that only apply to newly generated terrain, and says which is which.

| Key | Default | Effect |
|---|---|---|
| `spacing` | 5000 | Field wavelength. **The main control over how long a chasm is** |
| `rarity` | 0.0 | How much of the world may contain chasms. Higher is rarer |
| `oceanBias` | 0.35 | How much more readily chasms form under deep water |
| `minWidth` / `maxWidth` | 200 / 700 | Rim width range, in blocks |
| `wallScale` | 0.28 | Coarse wall shape, as a fraction of the chasm's own width |
| `wallRoughness` | 22 | Fine wall detail, in blocks |
| `floorNarrowing` | 0.62 | Floor width as a fraction of rim width. Higher is steeper |
| `terraceStrength` | 0.55 | How strongly walls are cut into benches. 0 gives a smooth cone |
| `terraceCount` | 7 | Benches from floor to rim. Fewer means taller cliffs |
| `removeBedrock` | true | Carve through the bedrock floor into the void |
| `drainWater` | true | Empty the sea sitting directly over a chasm |
| `blockStructures` | true | Suppress structures in affected chunks |
| `searchRadius` | 12000 | How far `locate` looks before giving up |

Chasms only appear in **newly generated** chunks; existing terrain is untouched. Changing a shape
value mid-world leaves a visible seam where new terrain meets old, because chasm geometry has to stay
fixed for chunks to line up along their shared borders.

## Building

Requires JDK 25. See `BUILDING.md` in the parent directory for environment notes, and `REVIEW.md`
here for design constraints and open issues.

```
gradlew build
```

---

## About this mod

Great Chasms is **original work**, not a fork of anything. It is written for my own modpack and is
not published to Modrinth or CurseForge, so this repository is the only source for it.

It is not a finished, polished release. It is developed against one pack on one machine, the shape
parameters are still being tuned, and the water behaviour in particular has not been verified across
a range of worlds. Treat any build from here accordingly.

All rights reserved.
