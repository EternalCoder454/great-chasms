# Great Chasms: code review and handoff

Written 2026-08-15 at the end of a long session. Records what was fixed, what is still open, and the
context a fresh session would otherwise have to rediscover the hard way.

---

## 1. Fixed in this review pass

### 1.1 `anySolidInRange` bounded at the wrong height (introduced same day)

```java
// wrong
if (!anySolidInRange(sections, minSectionY, bottom, Math.min(worldTop, profileRim))) return;
// right
if (!anySolidInRange(sections, minSectionY, bottom, worldTop)) return;
```

Above `profileRim` the profile clamps to `t = 1` and carves at **full width**, so material up there is
still in scope. Bounding the early-out at `profileRim` meant a tall feature standing over a chasm
could make the whole chunk skip its carve. Latent for about ten minutes; worth recording because the
early-out and the profile clamp were added in different turns and neither comment mentioned the
other.

### 1.2 Structure exclusion band far narrower than the hole

`intersectsChunk` tested `col.halfWidth + field.wallAmplitude()`, but the carve widens by
`maxWallOffset`, which includes the **coarse** term scaling with the chasm's own width. On a 700
block chasm that is roughly 98 blocks versus 22. Structures could legitimately start in a chunk the
chasm reached. Now both use `maxWallOffset`.

**Rule of thumb this violated:** any predicate that asks "does the chasm reach here" must use the
same width expression the carve uses. There are now two call sites; a third would be worth extracting.

### 1.3 Water spill marking roughly a quarter of the ocean

`touchesOpenAir` treats any block on a section boundary as exposed, and `lx == 0 || lx == 15 || ...`
is a *chunk* edge. So in an ocean chasm chunk, ~60 of 256 columns marked every water block in the
40-block scan band regardless of proximity to the chasm: thousands of scheduled fluid ticks plus a
`BlockPos` allocation each, per chunk, so that water hundreds of blocks away could re-evaluate and
settle straight back down.

The main loop now records a `nearChasm[256]` mask and the spill pass only considers the rim.

---

## 2. Open issues, roughly by value

### 2.1 The nine-pass carve is architecturally wasteful

`ChunkGeneratorMixin.greatchasms$reclearAfterDecoration` carves the chunk plus all eight neighbours,
so each chunk is carved nine times and each adjacent **pair** is handled twice. The early-out makes
repeat passes cheap now, but the design is still doing double work.

A deterministic ordering rule (only clean neighbours in the +X / +Z half-plane) would halve it, but
decoration order is not guaranteed, so this needs thought rather than a quick edit.

### 2.2 `ChasmField.sample` cost

Roughly nine fbm evaluations per column: five for the gradient-based distance, plus region, width and
profile. 256 columns per chunk. Fine for one pass, less fine multiplied by the pass count above.

The five distance evaluations are the obvious target — a cheaper analytic gradient, or reusing the
centre evaluation across a 2x2 column block, would cut it substantially.

### 2.3 Water spilling is still unverified

The mechanism (mark for post-processing **and** schedule a fluid tick in the past) is sound in
principle but has never been confirmed working in game. If curtains of static water still hang after
2.1.3, the fallback is to stop relying on fluid physics and carve a sloped spillway into the rim so
the water has somewhere to run. That is a shape change, not a fluid change.

### 2.4 Features can still bleed over the rim

Features may write outside their own chunk. The neighbour cleanup covers the common case, but a
feature rooted two chunks away with a large enough radius is not covered. Likely to show as small
stray blocks near the edge rather than anything dramatic.

### 2.5 `intersectsChunk` allocates

`new ChasmField.Column()` per call, once per chunk at STRUCTURE_STARTS. Trivial, but the carve path
already reuses one and this could too.

---

## 3. Design context worth not rediscovering

### 3.1 Why not a `WorldCarver`

A carver may only reach `getRange()` chunks from its origin, and raising that makes every chunk
iterate `(2r+1)^2` candidate origins. Unusable long before the reach is measured in thousands of
blocks. Chasms are therefore an implicit **level set of a global noise field**: each column asks a
pure function, no chunk needs its neighbours, and it is safe under C2ME's parallel generation. It is
also why `/greatchasms locate` can search without generating a single chunk.

### 3.2 The distance estimate and its two failure modes

`dist = |n| / |grad n|` converts a noise value into **blocks**, which is what lets the config express
widths as real block counts. It fails in two ways, and the fix for one causes the other:

- **Saddles.** Where two contour branches pinch, both `|n|` and the gradient approach zero, so the
  hump between them reads as far from any centreline and never gets carved. Symptom: uncarved masses
  standing mid-chasm. Fix: clamp the divisor from below.
- **Extrema.** At a local extremum the gradient falls away in *every* direction, so a hard enough
  clamp makes the whole disc satisfy `dist < halfWidth`. Symptom: **circular chasms**. Fix: a smaller
  clamp.

Currently `gradientFloor = pathFrequency * 0.35`. It has been 0.30 and 0.85. If circles reappear,
lower it; if mid-chasm masses reappear, raise it. A real fix would discriminate saddle from extremum
via curvature, which costs more samples.

### 3.3 The carve must stay idempotent

`carve()` re-primes the heightmaps, and it runs up to nine times per chunk. **Nothing in the carve may
derive its shape from a value the carve itself changes.** This was violated once: the vertical profile
was normalised by the column's own surface height, so each pass measured against a surface the
previous pass had lowered, each carved to a slightly different width, and columns surviving one pass
were cut by the next. The residue was thin vertical blades and radial streaks, which look exactly
like an LOD artifact and were misdiagnosed as one.

`profileRim = seaLevel + 72` is fixed for this reason. Do not "improve" it by making it follow terrain.

### 3.4 Stage ordering

`... NOISE -> SURFACE -> CARVERS -> FEATURES -> INITIALIZE_LIGHT -> LIGHT -> SPAWN -> FULL`

- Main carve at **`applyCarvers` TAIL**: after bedrock is placed so it can be removed, before features
  so nothing decorates blocks about to be deleted.
- Cleanup at **`applyBiomeDecoration` TAIL**: catches Streams Reflowing rivers and anything else that
  decorates into the hole.
- Structures suppressed at **`createStructures` HEAD**, which runs before terrain exists. Fine,
  because the field is a pure function of the seed and answerable before a block is placed.
- Note **FEATURES precedes LIGHT**, so Voxy WorldGen ingesting at LIGHT sees the post-cleanup state.
  The neighbour cleanup can still modify a neighbour that already reached LIGHT and was ingested,
  which would leave voxy holding stale LOD for that chunk. Unconfirmed, but plausible and worth
  checking if LOD ever disagrees with real blocks.

### 3.5 `NoiseBasedChunkGenerator`, not `ChunkGenerator`

`ChunkGenerator.applyCarvers` is abstract, so there is no body to inject into. Targeting the noise
generator is also correct in practice: Tectonic and Regions Unexplored ship noise settings and biomes
rather than replacing the generator.

### 3.6 Config traps

- Server config lands in `minecraft/config/greatchasms-server.toml`, **not** the world's
  `serverconfig/`. Looking in the wrong place produced a completely wrong diagnosis once.
- An existing file **freezes the defaults current when it was written**. Every later change to code
  defaults is silently ignored. If tuning appears to do nothing, delete the file.
- `ChasmConfig` falls back to hardcoded defaults when `SPEC.isLoaded()` is false. It must never
  default to *disabled*: v1 did, and the mod generated nothing while looking perfectly healthy in the
  log.
- Shape values are snapshotted per world seed in `ChasmField`. Anything that changes them must call
  `ChasmField.clearCache()` or the file and the running generation disagree.

### 3.7 Debugging tools already present

- `/greatchasms info` prints distance, half width, ocean factor, region gate and taper at the player.
  **Use this before tuning anything.** Several rounds were lost to reading screenshots instead.
- One-shot log line `Carved the first chasm of this session at chunk X, Z` distinguishes "no chasms
  nearby" from "mod silently disabled".

---

## 4. Method note

Four separate visual defects in this mod were misdiagnosed from screenshots and corrected only after
looking at the code that had changed most recently:

| Symptom | Guessed | Actually |
|---|---|---|
| No chasms at all | Tuning too rare | Config gate defaulted to disabled |
| Short lens-shaped pits | Wrong spacing | Stale config file overriding new defaults |
| Uncarved dome mid-chasm | Wall noise | Divide-by-small-gradient at a saddle |
| Vertical blades and radial streaks | Voxy LOD artifact | Carve was not idempotent |

The pattern: **when a visual defect appears, read the diff from the last change before theorising
about the maths.** Three of the four were introduced by the immediately preceding edit.
