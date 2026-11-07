package dev.swipixel.restyler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The layered world-look resolver. Plain Java, no Minecraft: WorldLook feeds it the biome samples, the time, the
 * weather and the player's position.
 *
 *   1 global look -> 2 biome base -> 3 time & weather (@ lines) -> 4 biome variation (~) -> 5 zones
 *
 * Layers 2-4 are evaluated per distinct biome around the player (each from the global look), then blended by the
 * summed sample weights. Every layer's output is clamped to 0..1. Layer 6 (the player's pick) is WorldLook's job.
 */
final class LookResolver {

    enum Precip { NONE, RAIN, SNOW }

    static final int L_GLOBAL = 0, L_BIOME = 1, L_TIME = 2, L_NOISE = 3, L_ZONES = 4, LAYERS = 5;
    static final String[] LAYER_NAMES = {"global", "biomes", "time & weather", "variation", "zones"};

    static final class Settings {
        boolean biomes = true, timeWeather = true, zones = true;
        float variation = 1f, wavelength = 450f;
        LookSpec global;                      // null = no global look
    }

    static final class Sample {
        final String id;
        float weight;
        final Precip precip;

        Sample(String id, float weight, Precip precip) {
            this.id = id;
            this.weight = weight;
            this.precip = precip;
        }
    }

    static final class Env {
        double x, y, z;
        float time;                           // timeOfDay % 24000
        float rain, thunder;
        float sky = 1f;                       // eased sky access 0..1
        boolean conditions = true;            // false in the Nether / End: no time or weather
        String salt = "";
        Precip here = Precip.RAIN;            // precipitation of the player's own biome (for zones)
    }

    static final class Result {
        final float[] look = new float[Slots.N];
        final float[][] delta = new float[LAYERS][Slots.N];       // what each layer changed (blended)
        final Map<String, float[]> zoneDelta = new LinkedHashMap<>();
        final Map<String, Float> zoneWeight = new LinkedHashMap<>();
        final Map<String, Float> biomes = new LinkedHashMap<>();  // normalized weights
        float[] cond = new float[BiomeTable.Cond.values().length];
        final List<String> why = new ArrayList<>();
    }

    private LookResolver() {}

    private static final Map<String, LookSpec> NAME_LOOKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** "birch_forest" / "mymod:crystal_caves" -> the prompt "birch forest" / "crystal caves" (resolved like /vibe). */
    static LookSpec nameLook(String biomeId) {
        return NAME_LOOKS.computeIfAbsent(biomeId, id -> {
            String n = id.substring(id.indexOf(':') + 1).replace('_', ' ').strip();
            return n.isEmpty() ? null : LookSpec.parse("\"" + n + "\"", LookSpec.Mode.BASE);
        });
    }

    /** Condition weights (Cond order) already multiplied by sky access; all 0 where there is no time / weather. */
    static float[] condWeights(Env e, boolean on) {
        float[] w = new float[BiomeTable.Cond.values().length];
        if (!on || !e.conditions) return w;
        float[] t = LookMath.timeWeights(e.time);
        float s = Math.max(0f, Math.min(1f, e.sky));
        w[BiomeTable.Cond.DAWN.ordinal()] = t[0] * s;
        w[BiomeTable.Cond.NOON.ordinal()] = t[1] * s;
        w[BiomeTable.Cond.DUSK.ordinal()] = t[2] * s;
        w[BiomeTable.Cond.NIGHT.ordinal()] = t[3] * s;
        w[BiomeTable.Cond.RAIN.ordinal()] = e.rain * s;
        w[BiomeTable.Cond.SNOW.ordinal()] = e.rain * s;
        w[BiomeTable.Cond.THUNDER.ordinal()] = e.thunder * s;
        return w;
    }

    /**
     * Applies the condition lines in a fixed order (dawn, noon, dusk, night, rain|snow, thunder). No precipitation:
     * @rain / @snow are ignored. Snowy: @snow replaces @rain if there is one. Rainy: @snow is ignored.
     */
    static void applyConds(float[] look, Function<BiomeTable.Cond, LookSpec> line, float[] w, Precip pr,
                           LookSpec.Prompts p) {
        for (BiomeTable.Cond c : new BiomeTable.Cond[]{BiomeTable.Cond.DAWN, BiomeTable.Cond.NOON,
                BiomeTable.Cond.DUSK, BiomeTable.Cond.NIGHT}) {
            LookSpec s = line.apply(c);
            if (s != null) s.apply(look, w[c.ordinal()], p);
        }
        if (pr != Precip.NONE) {
            LookSpec snow = pr == Precip.SNOW ? line.apply(BiomeTable.Cond.SNOW) : null;
            LookSpec wet = snow != null ? snow : line.apply(BiomeTable.Cond.RAIN);
            if (wet != null) wet.apply(look, w[BiomeTable.Cond.RAIN.ordinal()], p);
        }
        LookSpec th = line.apply(BiomeTable.Cond.THUNDER);
        if (th != null) th.apply(look, w[BiomeTable.Cond.THUNDER.ordinal()], p);
    }

    /** Variation offsets for one biome at (x, z): per slider value noise over the ~ range, times variation. */
    static void applyNoise(float[] look, LookSpec var, String biome, Env e, Settings s) {
        double wl = Math.max(1.0, s.wavelength);
        for (int i = 0; i < Slots.N; i++) {
            if (!var.var[i]) continue;
            float n = LookMath.valueNoise(e.x / wl, e.z / wl, LookMath.hash(e.salt, biome, i));
            float u = (n + 1f) * 0.5f;
            look[i] += (var.lo[i] + (var.hi[i] - var.lo[i]) * u) * s.variation;
        }
    }

    static Result resolve(Settings s, BiomeTable t, List<Sample> samples, Env e, List<Zones.Zone> zones,
                          LookSpec.Prompts p, boolean why) {
        Result r = new Result();
        float[] look = r.look;

        // 1 global
        if (s.global != null) s.global.apply(look, 1f, p);
        Slots.clamp(look);
        System.arraycopy(look, 0, r.delta[L_GLOBAL], 0, Slots.N);
        if (why) r.why.add("1 global: " + (s.global == null ? "off" : s.global.source + " -> " + Slots.summary(look)));

        float[] cw = condWeights(e, s.timeWeather);
        r.cond = cw;

        // 2-4 biomes, each resolved once from the global look, then blended
        float total = 0f;
        for (Sample b : samples) total += b.weight;
        float[] st0 = new float[Slots.N], st1 = new float[Slots.N], st2 = new float[Slots.N];
        if (total > 0f) {
            BiomeTable.Entry star = t.star();
            for (Sample b : samples) {
                float bw = b.weight / total;
                r.biomes.put(b.id, bw);
                BiomeTable.Entry be = t.get(b.id);
                LookSpec base = be != null && be.base != null ? be.base : star != null && star.base != null ? star.base
                        : nameLook(b.id);                      // no line of its own: what /vibe makes of the biome's name
                float[] v0 = look.clone();
                boolean baseOk = true;
                if (s.biomes && base != null) baseOk = base.apply(v0, 1f, p);
                Slots.clamp(v0);
                float[] v1 = v0.clone();
                applyConds(v1, c -> {
                    LookSpec l = be != null ? be.conds.get(c) : null;
                    return l != null ? l : star != null ? star.conds.get(c) : null;
                }, cw, b.precip, p);
                Slots.clamp(v1);
                float[] v2 = v1.clone();
                LookSpec var = null;
                if (s.biomes && s.variation > 0f) {
                    var = be != null && be.var != null ? be.var : star != null ? star.var : null;
                    if (var == null && base != null && baseOk) {
                        boolean[] nz = base.nonZero(p);
                        if (nz != null) var = LookSpec.autoVariation(nz);
                    }
                    if (var != null) applyNoise(v2, var, b.id, e, s);
                }
                Slots.clamp(v2);
                for (int i = 0; i < Slots.N; i++) {
                    st0[i] += bw * v0[i];
                    st1[i] += bw * v1[i];
                    st2[i] += bw * v2[i];
                }
                if (why) {
                    float[] d1 = new float[Slots.N], d2 = new float[Slots.N];
                    for (int i = 0; i < Slots.N; i++) { d1[i] = v1[i] - v0[i]; d2[i] = v2[i] - v1[i]; }
                    String src = be != null && be.base != null ? "" : star != null && star.base != null ? " (from *)"
                            : " (from its name)";
                    r.why.add(String.format(Locale.ROOT, "  %s %d%%: base %s%s | time/weather %s | variation %s",
                            b.id, Math.round(bw * 100),
                            !s.biomes ? "off" : base == null ? "none" : !baseOk ? "waiting for prompt" : Slots.summary(v0),
                            src, Slots.signed(d1), var == null ? "none" : Slots.signed(d2)));
                }
            }
            for (int i = 0; i < Slots.N; i++) {
                r.delta[L_BIOME][i] = st0[i] - look[i];
                r.delta[L_TIME][i] = st1[i] - st0[i];
                r.delta[L_NOISE][i] = st2[i] - st1[i];
            }
            System.arraycopy(st2, 0, look, 0, Slots.N);
        }
        if (why) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Float> en : r.biomes.entrySet())
                b.append(b.length() > 0 ? ", " : "").append(en.getKey()).append(' ').append(Math.round(en.getValue() * 100)).append('%');
            r.why.add(1, "2-4 biomes" + (s.biomes ? "" : " (base + variation off)") + ": " + (b.length() == 0 ? "none" : b));
            StringBuilder c = new StringBuilder();
            for (BiomeTable.Cond k : BiomeTable.Cond.values())
                if (k != BiomeTable.Cond.SNOW) c.append(k.key).append(String.format(Locale.ROOT, " %.2f  ", cw[k.ordinal()]));
            r.why.add(String.format(Locale.ROOT, "  conditions%s: %s(sky %.2f)",
                    !s.timeWeather ? " (off)" : !e.conditions ? " (none in this dimension)" : "", c, e.sky));
        }

        // 5 zones, ascending priority
        if (s.zones && zones != null) {
            for (Zones.Zone z : zones) {
                float w = z.weight(e.x, e.y, e.z);
                r.zoneWeight.put(z.name, w);
                float[] before = look.clone();
                if (w > 0f) {
                    float[] zt = look.clone();
                    if (z.spec.apply(zt, 1f, p)) {
                        applyConds(zt, z.conds::get, cw, e.here, p);
                        Slots.clamp(zt);
                        for (int i = 0; i < Slots.N; i++) look[i] += (zt[i] - look[i]) * w;
                        Slots.clamp(look);
                    }
                }
                float[] d = new float[Slots.N];
                for (int i = 0; i < Slots.N; i++) {
                    d[i] = look[i] - before[i];
                    r.delta[L_ZONES][i] += d[i];
                }
                r.zoneDelta.put(z.name, d);
                if (why && w > 0f)
                    r.why.add(String.format(Locale.ROOT, "5 zone %s (prio %d, %s) weight %.2f: %s", z.name, z.priority,
                            z.offset ? "offset" : "set", w, Slots.signed(d)));
            }
        }
        if (why) {
            if (!s.zones) r.why.add("5 zones: off");
            else if (r.zoneWeight.values().stream().noneMatch(w -> w > 0f)) r.why.add("5 zones: none here");
            r.why.add("= world look: " + Slots.summary(look));
        }
        return r;
    }
}
