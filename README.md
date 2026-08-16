# Great Chasms

![Great Chasms](src/main/resources/icon.png)

Minecraft's ravines are a few dozen blocks deep and easy to miss. Great Chasms replaces that idea
with something worth the name: rifts that run for **thousands of blocks**, open **hundreds of blocks
wide**, and drop straight past bedrock into the void. Crossing one means flying or building a bridge.

They cut through ocean floor and continent alike, narrowing as they come ashore. Because they expose
the entire stratigraphy from grass to deepslate in one wall, they are also the fastest way to find
ore in the game.

**NeoForge 26.1.2**, Java 25. Original work by EternalCoder454.

## Compatibility

Built to sit alongside **Tectonic**, **Regions Unexplored**, **Streams Reflowing**, **C2ME** and
**Lithostitched**. All are optional; none are required.

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
wide is the chasm there". No chunk ever needs to know about its neighbours, which is also what makes
it safe under C2ME's parallel generation, and what lets `/greatchasms locate` find a chasm without
generating a single chunk.

Distance is estimated as `|n| / |grad n|`, which turns an opaque noise value into a figure measured
in blocks, so widths can be configured as real block counts rather than magic thresholds.

Carving runs at the tail of `applyCarvers`: after the noise and surface passes, so bedrock can be
removed, and before features, so nothing decorates blocks that are about to be deleted. A second
pass after decoration re-empties the volume, which handles anything that writes into the hole later
without needing to know those mods exist.

Ocean water is not painted in. The sea is already an infinite source and the fluid system will
cascade it down the walls on its own; generation simply fires no block updates, so exposed fluid is
marked and given an explicit tick to start it falling.

## Commands

| Command | Effect |
|---|---|
| `/greatchasms locate` | Nearest chasm, searched analytically without loading chunks |
| `/greatchasms info` | Field values at your feet: distance, width, ocean factor, gate state |

## Configuration

Server scoped, so it lives in the world's config and travels with the save. Chasm shape must stay
fixed for a world's life or chunks generated before and after an edit would not line up.

Install **[Cloth Config](https://modrinth.com/mod/cloth-config)** for an in-game screen, reachable
from the Config button in the mod list. It is entirely optional: without it the mod behaves
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
| `floorNarrowing` | 0.42 | Floor width as a fraction of rim width |
| `removeBedrock` | true | Carve through the bedrock floor into the void |
| `drainWater` | true | Empty the sea sitting directly over a chasm |
| `blockStructures` | true | Suppress structures in affected chunks |

Chasms only appear in **newly generated** chunks. Existing terrain is untouched.

## Building

Requires JDK 25. See `BUILDING.md` in the parent directory for the environment notes.

```
gradlew build
```
