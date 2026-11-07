# Restyler 1.1.89: shader looks for Minecraft

Fabric 1.21.1 + Fabric API, client only. Put `restyler-4.0.0.jar` into `mods`. The optional CLIP text encoder is installed automatically from the jar the first time `/vibe` is used.
Every look is a full-screen GPU pass built from the game's depth buffer. There's no AI network: the AI mode was
removed in 4.0.0.

Measured: about 70–80 FPS on a GTX 1650 with several looks on, and about 10 FPS on an Intel HD 4600.

## Keys and commands
- **O**: menu (Moods | Styles | Effects, Restyler on/off, World looks). **K**: on/off. Both can be changed in Options > Controls.
- `/restyler`: status.
- `/restyler mood|style|effect <name> <0-100>`, `/restyler mood off` (and the same for `style` and `effect`).
- `/vibe <words>`: CLIP picks a mix of moods, styles and effects from text. `/vibe off` clears them all. Both pin your
  look (see World looks).

## World looks
The look follows where you are: each biome has its own look, blended over ~40 blocks at borders. Time of day and
weather add to it (`@dawn`, `@noon`, `@dusk`, `@night`, `@rain`, `@snow`, `@thunder`), and these fade out where there's
no sky (caves). Every spot also varies slightly and deterministically, so no two deserts look the same. Zones you place
override it locally.

- **Your pick wins:** moving any slider or running `/vibe <text>` *pins* your sliders. `/vibe auto` (or **O > World
  looks > World looks: ON**) hands control back to the world. World looks never overwrite your saved slider files.
- **Menu:** O > World looks: Biomes / Time & weather / Zones toggles, Variation %, Fade seconds, and what is active here.
- **Files** in `config/restyler/`:
  - `world.properties`: switches, `variation`, `fade_seconds`, `wet_rise_seconds` / `wet_dry_seconds`, `global`.
  - `biomes.txt`: optional. A copy here overrides the built-in table, like `vibes.txt`.
  - `zones/<world>.json`: zones per world.
- **Commands:**
  - `/vibe auto` | `/vibe global <text>|off`
  - `/vibe biome here|<biome> <text>` | `@<cond> <line>` | `~ <ranges>` | `reset`, and `/vibe biome list`
  - `/vibe zone add <name> <radius> [blend] [priority] <text>` | `box <name> <x1 y1 z1 x2 y2 z2> [blend] [priority] <text>`
    | `remove` | `list` | `edit` | `fade <name> time|hard|crossfade`
  - `/vibe world reload` | `/vibe why` (what each layer adds here)
- **Look syntax** (`biomes.txt`, zones, global):
  - `golden_hour 50, apocalyptic 40` (set; sliders you don't name are 0)
  - `@` lines: `apocalyptic +15` is an offset, `wet 40` moves wet toward 40
  - `~` lines: `golden_hour 15` = ±15 %, `misty_morning 0..15`
  - `"a prompt"` is resolved once through the vibe table / CLIP and then cached.
  - A zone whose values are all `+N`/`-N` is an offset zone.

## Moods
`dark_fantasy, aurora_night, gothic, apocalyptic, horror, golden_hour, cozy, dreamy, cinematic, vibrant,
misty_morning, neon_night, frozen, stormy`

- Each mood has a % slider, and moods can be mixed.
- Fog grows with the real distance. The sky gets the mood's gradient and aurora.
- Torch bloom comes only from real light blocks. Torch light shafts are occluded by blocks, and so are the sun and moon rays.
- The Brightness slider is in the same menu.
- Saved in `config/restyler/moods.properties`.

## Styles
`anime, pastel, oil_paint, watercolor, comic, sketch, noir, old_film, vaporwave, retro_green`

- Styles mix with each other.
- `pixelate` and `crt` stack on top of any style.
- Saved in `config/restyler/styles.properties`.

## Effects
`wet, snow, overgrown, realistic, depth_of_field, tilt_shift`

- **wet**: blurred reflections of the scene on wet patches.
- **snow**: uneven caps that skip plants.
- **overgrown**: moss on blocks.
- **realistic**: sun shadows, ambient occlusion and haze.
- **depth_of_field**: focuses where the crosshair looks, with the focus easing like a camera. It's drawn last, so it
  blurs the styled image.
- **tilt_shift**: a miniature look.

## /vibe (optional)
- **Setup:** no manual setup is needed. This release bundles the CLIP text encoder from the configured Minecraft profile and installs it with its matching tokenizer to `config/restyler/clip/` on first `/vibe` use.
- **Offline/manual setup:** place `clip_text.onnx` and `tokenizer.json` (or `vocab.json` + `merges.txt`) in `config/restyler/clip/`. An existing CLIP folder at `config/snowfilter/clip/` is also detected.
- **The vibe table:** `vibes.txt` maps words to slider settings, and CLIP only matches your text to the closest lines.
  - The built-in table is in the jar.
  - A copy at `config/restyler/vibes.txt` overrides it.
  - The first run caches the table in `vibes_cache.bin`.
- **Runtime:** CLIP runs on the CPU, off the game thread.

## Build
1. `gradlew build` with Minecraft closed. The jar is written to `build\libs\restyler-4.0.0.jar`.
2. `copyit.bat` copies it into the instance. Old restyler jars are parked in `mods_off`.
3. `publish.bat` makes a git commit and pushes it.

## Upgrading from 3.x
- The model files in `config/restyler` (`*.onnx`, `styles.json`, `vignette.json`, `cuda/`) aren't used any more and
  can be deleted. Keep `clip/` if you use `/vibe`.
- Old AI settings in `restyler.properties` are ignored. The file is rewritten with only `enabled`.
