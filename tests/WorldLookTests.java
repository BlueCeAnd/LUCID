package dev.swipixel.restyler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Plain-Java tests for the world-look core (no Minecraft on the class path).
 *
 *   javac -cp build/classes/java/main:<gson.jar> -d out tests/WorldLookTests.java
 *   java  -cp out:build/classes/java/main:<gson.jar> dev.swipixel.restyler.WorldLookTests <biomes.txt> [vanilla ids]
 */
public final class WorldLookTests {

    private static int pass = 0, fail = 0;

    private static void check(boolean ok, String what) {
        if (ok) pass++;
        else {
            fail++;
            System.out.println("  FAIL: " + what);
        }
    }

    private static void near(float a, float b, float eps, String what) {
        check(Math.abs(a - b) <= eps, what + " (got " + a + ", want " + b + ")");
    }

    private static int slot(String k) {
        int i = Slots.index(k);
        if (i < 0) throw new IllegalStateException(k);
        return i;
    }

    private static final LookSpec.Prompts NO_PROMPTS = p -> null;

    public static void main(String[] args) throws Exception {
        Path biomes = Path.of(args.length > 0 ? args[0] : "src/main/resources/assets/restyler/biomes.txt");
        Path vanilla = args.length > 1 ? Path.of(args[1]) : null;
        System.out.println("Slots: " + Slots.N + " (" + Slots.NM + " moods, " + Slots.NS + " styles, " + Slots.NE + " effects), wet = slot " + Slots.WET);

        parser();
        defaultTable(biomes, vanilla);
        conditionWeights();
        noise();
        zones();
        layerOrder();
        precipitationAndSky();
        borderCrossing();
        wetEase();
        performance(biomes);

        System.out.println();
        System.out.println(fail == 0 ? "ALL PASSED (" + pass + " checks)" : fail + " FAILED, " + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------ parser

    private static void parser() {
        System.out.println("\n[parser]");
        List<String> lines = List.of(
                "desert            = golden_hour 50, apocalyptic 40          ; set",
                "desert @noon      = apocalyptic +15, vibrant -10            ; offset",
                "desert @dusk      = golden_hour +25",
                "desert @night     = frozen +20, cinematic +15",
                "desert @rain      = stormy +30, wet 40",
                "desert ~          = golden_hour 15, apocalyptic 15, vibrant 10, misty_morning 0..15",
                "* @thunder        = stormy +40",
                "plains            = \"soft green morning, gentle light\"      ; prompt; with a comma and ; inside? no",
                "minecraft:forest  = cozy 30",
                "mymod:ash_waste   = apocalyptic 60",
                "# a comment line",
                "   ",
                "taiga@dawn = misty_morning +30",
                "quoted = \"a; b\"",
                // errors
                "badbase = golden_hour +10, frozen -5",
                "typo = goldenhour 50",
                "badnum = golden_hour fifty",
                "nan = golden_hour NaN",
                "badcond @teatime = frozen +10",
                "no equals sign here",
                "two words = cozy 10",
                "unclosed = \"open prompt",
                "rangeprompt ~ = \"nope\"",
                "badpart = cozy",
                "desert = golden_hour 55");
        BiomeTable t = BiomeTable.parse(lines, "test.txt");
        for (String w : t.warnings) System.out.println("  warning: " + w);
        check(t.warnings.size() == 11, "11 warnings, got " + t.warnings.size());
        check(t.warnings.stream().anyMatch(w -> w.startsWith("test.txt:15:") && w.contains("only offsets")), "offset-only base -> warning with line");
        check(t.warnings.stream().anyMatch(w -> w.startsWith("test.txt:16:") && w.contains("goldenhour")), "unknown slider named");
        check(t.warnings.stream().anyMatch(w -> w.startsWith("test.txt:17:") && w.contains("bad number")), "bad number");
        check(t.warnings.stream().anyMatch(w -> w.startsWith("test.txt:18:") && w.contains("bad number")), "NaN is a bad number");
        check(t.warnings.stream().anyMatch(w -> w.startsWith("test.txt:19:")), "unknown condition");
        check(t.warnings.stream().anyMatch(w -> w.startsWith("test.txt:20:") && w.contains("missing '='")), "missing =");
        check(t.warnings.stream().anyMatch(w -> w.startsWith("test.txt:25:") && w.contains("twice")), "duplicate key warns");
        check(t.get("badbase") == null && t.get("typo") == null, "invalid lines skipped");

        BiomeTable.Entry d = t.get("desert");
        check(d != null && d.base != null && d.conds.size() == 4 && d.var != null, "desert: base + 4 conds + ~");
        near(d.base.val[slot("golden_hour")], 0.55f, 1e-6f, "later duplicate wins");
        check(d.base.op[slot("cozy")] == LookSpec.SET && d.base.val[slot("cozy")] == 0f, "base: unnamed sliders set to 0");
        LookSpec noon = d.conds.get(BiomeTable.Cond.NOON);
        check(noon.op[slot("apocalyptic")] == LookSpec.ADD && Math.abs(noon.val[slot("vibrant")] + 0.10f) < 1e-6, "@noon offsets");
        check(noon.op[slot("cozy")] == LookSpec.NONE, "@ line leaves unnamed sliders alone");
        LookSpec rain = d.conds.get(BiomeTable.Cond.RAIN);
        check(rain.op[slot("wet")] == LookSpec.SET && rain.op[slot("stormy")] == LookSpec.ADD, "@rain mixes set (wet 40) and offset");
        near(d.var.lo[slot("golden_hour")], -0.15f, 1e-6f, "~ N = -N..");
        near(d.var.hi[slot("golden_hour")], 0.15f, 1e-6f, "~ N = ..+N");
        near(d.var.lo[slot("misty_morning")], 0f, 1e-6f, "~ 0..15 low");
        near(d.var.hi[slot("misty_morning")], 0.15f, 1e-6f, "~ 0..15 high");
        check(t.star() != null && t.star().conds.containsKey(BiomeTable.Cond.THUNDER), "* @thunder");
        check(t.get("plains").base.isPrompt() && t.get("plains").base.prompt.equals("soft green morning, gentle light"), "prompt kept, ; inside quotes ok");
        check(t.get("quoted").base.prompt.equals("a; b"), "; inside quotes is not a comment");
        check(t.get("forest") != null, "minecraft: prefix stripped");
        check(t.get("mymod:ash_waste") != null, "modded id keeps namespace");
        check(t.get("taiga").conds.containsKey(BiomeTable.Cond.DAWN), "desert@dawn without space");

        // apply semantics
        float[] look = new float[Slots.N];
        look[slot("cozy")] = 0.7f;
        d.base.apply(look, 1f, NO_PROMPTS);
        near(look[slot("cozy")], 0f, 0f, "base resets unnamed");
        near(look[slot("golden_hour")], 0.55f, 1e-6f, "base sets");
        rain.apply(look, 0.5f, NO_PROMPTS);
        near(look[slot("wet")], 0.20f, 1e-6f, "@rain wet 40 at weight 0.5 -> lerp 0..0.4 = 0.2");
        near(look[slot("stormy")], 0.15f, 1e-6f, "@rain stormy +30 at 0.5 -> +0.15");
        check(!t.get("plains").base.apply(look, 1f, NO_PROMPTS), "unresolved prompt: layer skipped");
        Map<String, float[]> pm = new HashMap<>();
        float[] pv = new float[Slots.N];
        pv[slot("dreamy")] = 0.4f;
        pm.put("soft green morning, gentle light", pv);
        float[] l2 = new float[Slots.N];
        l2[slot("cozy")] = 0.9f;
        check(t.get("plains").base.apply(l2, 1f, pm::get), "resolved prompt applies");
        near(l2[slot("dreamy")], 0.4f, 1e-6f, "prompt base sets");
        near(l2[slot("cozy")], 0f, 1e-6f, "prompt base: unnamed = 0");

        // editing
        List<String> small = List.of("desert = golden_hour 50", "desert @noon = apocalyptic +15", "plains = cozy 30");
        List<String> ed = BiomeTable.setLine(small, new BiomeTable.Key("desert", BiomeTable.Cond.DAWN, false), "misty_morning +20");
        check(ed.size() == 4 && ed.get(2).equals("desert @dawn = misty_morning +20"), "setLine adds after the biome's last line");
        List<String> ed2 = BiomeTable.setLine(ed, new BiomeTable.Key("desert", null, false), "golden_hour 10");
        check(ed2.size() == ed.size() && ed2.get(0).equals("desert = golden_hour 10"), "setLine replaces");
        List<String> builtin = List.of("# x", "desert = golden_hour 50", "desert @noon = apocalyptic +15", "plains = cozy 30");
        List<String> cfg = List.of("# x", "desert = golden_hour 10", "desert @dawn = misty_morning +5", "plains = cozy 30");
        check(BiomeTable.customBiomes(cfg, builtin).equals(Set.of("desert")), "customBiomes finds desert only");
        List<String> reset = BiomeTable.resetBiome(cfg, builtin, "desert");
        check(BiomeTable.customBiomes(reset, builtin).isEmpty(), "resetBiome restores");
        check(LookSpec.onlyOffsets("horror +30, gothic +10") && !LookSpec.onlyOffsets("horror 30"), "onlyOffsets");
    }

    // ------------------------------------------------------------ the shipped table

    private static void defaultTable(Path file, Path vanillaIds) throws Exception {
        System.out.println("\n[default biomes.txt]");
        BiomeTable t = BiomeTable.parse(Files.readAllLines(file), "biomes.txt");
        for (String w : t.warnings) System.out.println("  warning: " + w);
        check(t.warnings.isEmpty(), "no warnings in the shipped table");
        Set<String> nether = Set.of("nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas");
        Set<String> end = Set.of("the_end", "end_highlands", "end_midlands", "small_end_islands", "end_barrens");
        int lensOrStyle = 0;
        for (BiomeTable.Entry e : t.entries.values()) {
            List<LookSpec> all = new ArrayList<>(e.conds.values());
            if (e.base != null) all.add(e.base);
            for (LookSpec s : all) {
                for (int i = Slots.NM; i < Slots.NM + Slots.NS; i++) if (s.op[i] != LookSpec.NONE && s.val[i] != 0f) lensOrStyle++;
                for (int i = Slots.NM + Slots.NS + Effect.DOF; i < Slots.N; i++) if (s.op[i] != LookSpec.NONE && s.val[i] != 0f) lensOrStyle++;
            }
        }
        check(lensOrStyle == 0, "defaults use moods and effects only (no styles / DOF / tilt-shift)");
        if (vanillaIds != null) {
            Set<String> ids = new TreeSet<>(Files.readAllLines(vanillaIds));
            ids.removeIf(String::isBlank);
            Set<String> missing = new TreeSet<>(ids);
            missing.removeAll(t.entries.keySet());
            check(missing.isEmpty(), "every vanilla biome has lines, missing: " + missing);
            Set<String> extra = new TreeSet<>(t.entries.keySet());
            extra.removeAll(ids);
            extra.remove("*");
            check(extra.isEmpty(), "no unknown biome ids, extra: " + extra);
            int ow = 0;
            for (String id : ids) {
                BiomeTable.Entry e = t.get(id);
                if (e == null) continue;
                check(e.base != null, id + " has a base line");
                if (nether.contains(id) || end.contains(id)) {
                    check(e.conds.isEmpty(), id + ": Nether / End have no conditions");
                } else {
                    ow++;
                    check(e.conds.size() >= 2 && e.conds.size() <= 4,
                            id + ": 2-4 condition lines (got " + e.conds.size() + ")");
                    check(e.var != null, id + " has a ~ line");
                }
            }
            System.out.println("  " + ids.size() + " vanilla biomes, " + ow + " overworld with base + conditions + ~, "
                    + nether.size() + " Nether, " + end.size() + " End");
        }
    }

    // ------------------------------------------------------------ time weights

    private static void conditionWeights() {
        System.out.println("\n[condition weights over a day]");
        String[] n = {"dawn", "noon", "dusk", "night"};
        float[] maxStep = new float[4];
        float maxSumStep = 0f, minSum = 9f, maxSum = 0f;
        boolean inRange = true;
        float[] prev = LookMath.timeWeights(0f);
        float prevSum = sum(prev);
        for (int k = 1; k <= 24000 * 4; k++) {           // quarter ticks, all the way round (24000 == 0)
            float t = k * 0.25f;
            float[] w = LookMath.timeWeights(t);
            float s = sum(w);
            for (int i = 0; i < 4; i++) maxStep[i] = Math.max(maxStep[i], Math.abs(w[i] - prev[i]) * 4f);
            maxSumStep = Math.max(maxSumStep, Math.abs(s - prevSum) * 4f);
            minSum = Math.min(minSum, s);
            maxSum = Math.max(maxSum, s);
            for (int i = 0; i < 4; i++) inRange &= w[i] >= 0f && w[i] <= 1f;
            prev = w;
            prevSum = s;
        }
        check(inRange, "every weight in 0..1 at every quarter tick");
        for (int i = 0; i < 4; i++) {
            System.out.printf(Locale.ROOT, "  %-5s max change per tick %.5f (a jump would be ~1)%n", n[i], maxStep[i]);
            check(maxStep[i] < 0.002f, n[i] + " has no jump");
        }
        System.out.printf(Locale.ROOT, "  sum   max change per tick %.5f, range %.3f .. %.3f%n", maxSumStep, minSum, maxSum);
        check(maxSumStep < 0.003f, "sum has no jump");
        float[] wrapA = LookMath.timeWeights(23999.75f), wrapB = LookMath.timeWeights(0f);
        check(Math.abs(wrapA[0] - wrapB[0]) < 1e-4f, "dawn continuous across 23999 -> 0");
        near(LookMath.timeWeights(0f)[0], 1f, 1e-6f, "dawn peaks at 0");
        near(LookMath.timeWeights(12000f)[2], 1f, 1e-6f, "dusk peaks at 12000");
        near(LookMath.timeWeights(6000f)[1], 1f, 1e-6f, "noon = 1 at 6000");
        near(LookMath.timeWeights(18000f)[3], 1f, 1e-6f, "night = 1 at 18000");
        System.out.print("  t:");
        for (int t = 0; t < 24000; t += 2000) System.out.printf(Locale.ROOT, "  %5d", t);
        System.out.println();
        for (int i = 0; i < 4; i++) {
            System.out.printf(Locale.ROOT, "  %-5s", n[i]);
            for (int t = 0; t < 24000; t += 2000) System.out.printf(Locale.ROOT, "  %5.2f", LookMath.timeWeights(t)[i]);
            System.out.println();
        }
    }

    private static float sum(float[] w) {
        float s = 0f;
        for (float x : w) s += x;
        return s;
    }

    // ------------------------------------------------------------ noise

    private static void noise() {
        System.out.println("\n[biome noise]");
        long seed = LookMath.hash("New World", "desert", slot("golden_hour"));
        check(seed == LookMath.hash("New World", "desert", slot("golden_hour")), "hash is deterministic");
        check(seed != LookMath.hash("New World (1)", "desert", slot("golden_hour")), "different salt -> different seed");
        check(seed != LookMath.hash("New World", "plains", slot("golden_hour")), "different biome -> different seed");
        check(seed != LookMath.hash("New World", "desert", slot("apocalyptic")), "different slider -> different seed");
        float a = LookMath.valueNoise(123.456, -78.9, seed), b = LookMath.valueNoise(123.456, -78.9, seed);
        check(a == b, "same spot -> exactly the same value");
        float mn = 9, mx = -9, maxStep = 0, mean = 0;
        int n = 0;
        double wl = 450;
        float prev = LookMath.valueNoise(-5000 / wl, 300 / wl, seed);
        for (double x = -5000; x <= 5000; x += 1) {           // 1-block steps
            float v = LookMath.valueNoise(x / wl, 300 / wl, seed);
            mn = Math.min(mn, v);
            mx = Math.max(mx, v);
            maxStep = Math.max(maxStep, Math.abs(v - prev));
            mean += v;
            n++;
            prev = v;
        }
        System.out.printf(Locale.ROOT, "  10000 blocks: range %.3f .. %.3f, mean %.3f, max change per block %.5f%n", mn, mx, mean / n, maxStep);
        check(mn >= -1f && mx <= 1f, "noise within -1..1");
        check(maxStep < 0.02f, "noise changes smoothly per block (wavelength 450)");
        check(mx - mn > 0.8f, "noise actually varies");
        // the resolver at the same spot twice / in a "new session" (new objects)
        BiomeTable t = BiomeTable.parse(List.of("desert = golden_hour 50, apocalyptic 40", "desert ~ = golden_hour 15, misty_morning 0..15"), "t");
        LookResolver.Env e = env(1234.5, 70, -987.25, 6000);
        float[] r1 = resolve(t, e, List.of(new LookResolver.Sample("desert", 1f, LookResolver.Precip.NONE))).look;
        BiomeTable t2 = BiomeTable.parse(List.of("desert = golden_hour 50, apocalyptic 40", "desert ~ = golden_hour 15, misty_morning 0..15"), "t");
        float[] r2 = resolve(t2, env(1234.5, 70, -987.25, 6000), List.of(new LookResolver.Sample("desert", 1f, LookResolver.Precip.NONE))).look;
        check(java.util.Arrays.equals(r1, r2), "standing still / new session: identical look");
        System.out.printf(Locale.ROOT, "  desert at (1234.5, -987.25): golden_hour %.3f (base 0.50 +-0.15), misty_morning %.3f (0..0.15)%n",
                r1[slot("golden_hour")], r1[slot("misty_morning")]);
        check(Math.abs(r1[slot("golden_hour")] - 0.5f) <= 0.15f + 1e-6f, "variation within its range");
        check(r1[slot("misty_morning")] >= 0f && r1[slot("misty_morning")] <= 0.15f + 1e-6f, "0..15 range respected");
        // automatic +-10 % only on the base's non-zero sliders
        BiomeTable t3 = BiomeTable.parse(List.of("plains = cozy 30, vibrant 25"), "t");
        float maxOther = 0f, maxDev = 0f;
        for (int k = 0; k < 200; k++) {
            float[] r = resolve(t3, env(k * 37.0, 64, k * -53.0, 6000), List.of(new LookResolver.Sample("plains", 1f, LookResolver.Precip.RAIN))).look;
            for (int i = 0; i < Slots.N; i++) if (i != slot("cozy") && i != slot("vibrant")) maxOther = Math.max(maxOther, r[i]);
            maxDev = Math.max(maxDev, Math.abs(r[slot("cozy")] - 0.30f));
        }
        check(maxOther == 0f, "no ~ line: other sliders stay 0");
        check(maxDev <= 0.10f + 1e-6f && maxDev > 0.02f, "no ~ line: +-10 % on the base sliders (max seen " + maxDev + ")");
    }

    // ------------------------------------------------------------ zones

    private static void zones() {
        System.out.println("\n[zone weights]");
        Zones.Zone s = new Zones.Zone();
        s.name = "s"; s.cx = 0; s.cy = 64; s.cz = 0; s.radius = 10; s.blend = 8;
        near(s.weight(0, 64, 0), 1f, 0f, "sphere centre = 1");
        near(s.weight(10, 64, 0), 1f, 0f, "sphere edge = 1");
        near(s.weight(14, 64, 0), 0.5f, 1e-6f, "half the blend = 0.5");
        near(s.weight(18, 64, 0), 0f, 0f, "edge + blend = 0");
        near(s.weight(30, 64, 0), 0f, 0f, "far = 0");
        float prev = 1f;
        boolean mono = true;
        for (double d = 10; d <= 18; d += 0.1) {
            float w = s.weight(d, 64, 0);
            mono &= w <= prev + 1e-6f;
            prev = w;
        }
        check(mono, "weight falls monotonically over the blend");
        Zones.Zone b = new Zones.Zone();
        b.name = "b"; b.box = true; b.x1 = 0; b.y1 = 60; b.z1 = 0; b.x2 = 10; b.y2 = 70; b.z2 = 10; b.blend = 4;
        near(b.weight(5, 65, 5), 1f, 0f, "inside box = 1");
        near(b.weight(12, 65, 5), 0.5f, 1e-6f, "2 blocks out of a box, blend 4 = 0.5");
        near(b.weight(13, 73, 5), 0f, 0f, "corner distance (3,3) > 4 = 0");
        near(b.weight(5, 65, 5), 1f, 0f, "box");
        Zones.Zone h = new Zones.Zone();
        h.name = "h"; h.radius = 5; h.blend = 0;
        near(h.weight(5.01, 0, 0), 0f, 0f, "blend 0 = hard edge");
        System.out.printf(Locale.ROOT, "  sphere r=10 blend=8: d=10 %.2f, 12 %.2f, 14 %.2f, 16 %.2f, 18 %.2f%n",
                s.weight(10, 64, 0), s.weight(12, 64, 0), s.weight(14, 64, 0), s.weight(16, 64, 0), s.weight(18, 64, 0));
    }

    // ------------------------------------------------------------ layer order

    private static LookResolver.Env env(double x, double y, double z, float time) {
        LookResolver.Env e = new LookResolver.Env();
        e.x = x; e.y = y; e.z = z; e.time = time;
        e.salt = "Test World";
        return e;
    }

    private static LookResolver.Result resolve(BiomeTable t, LookResolver.Env e, List<LookResolver.Sample> samples) {
        return LookResolver.resolve(new LookResolver.Settings(), t, samples, e, List.of(), NO_PROMPTS, false);
    }

    private static void layerOrder() {
        System.out.println("\n[layer order]");
        BiomeTable t = BiomeTable.parse(List.of(
                "desert = golden_hour 90, apocalyptic 40",
                "desert @noon = golden_hour +30, frozen 60",     // 0.9 + 0.3 -> clamped to 1 before noise
                "desert ~ = golden_hour 0..0",                    // no variation, to see the clamp
                "plains = cozy 40",
                "plains ~ = cozy 0..0",
                "* @dusk = golden_hour +10"), "t");
        LookResolver.Settings s = new LookResolver.Settings();
        s.global = LookSpec.parse("stormy 50, cozy 20", LookSpec.Mode.BASE);
        // 1 global only (no biome samples)
        LookResolver.Result r = LookResolver.resolve(s, t, List.of(), env(0, 64, 0, 6000), List.of(), NO_PROMPTS, true);
        near(r.look[slot("stormy")], 0.5f, 1e-6f, "global alone");
        // 2 the biome base (a set line) replaces the global look
        LookResolver.Env noon = env(0, 64, 0, 6000);
        r = LookResolver.resolve(s, t, List.of(new LookResolver.Sample("desert", 1f, LookResolver.Precip.NONE)), noon, List.of(), NO_PROMPTS, true);
        for (String l : r.why) System.out.println("  " + l);
        near(r.look[slot("stormy")], 0f, 1e-6f, "biome set line overwrites global (unnamed = 0)");
        near(r.look[slot("golden_hour")], 1f, 1e-6f, "3 @noon offset after base, clamped to 1");
        near(r.look[slot("frozen")], 0.6f, 1e-6f, "@noon plain value sets (weight 1 at noon)");
        near(r.delta[LookResolver.L_TIME][slot("golden_hour")], 0.1f, 1e-5f, "time layer delta is the clamped 0.1");
        // biome without a base line keeps the global look; * conditions apply to it
        r = LookResolver.resolve(s, t, List.of(new LookResolver.Sample("modded:thing", 1f, LookResolver.Precip.RAIN)), env(0, 64, 0, 12000), List.of(), NO_PROMPTS, false);
        near(r.look[slot("stormy")], 0.5f, 1e-6f, "unknown biome: global look stays");
        near(r.look[slot("golden_hour")], 0.1f, 1e-6f, "unknown biome: * @dusk applies");
        // blending: 50 / 50
        r = LookResolver.resolve(s, t, List.of(new LookResolver.Sample("desert", 2f, LookResolver.Precip.NONE),
                new LookResolver.Sample("plains", 2f, LookResolver.Precip.RAIN)), env(0, 64, 0, 18000), List.of(), NO_PROMPTS, false);
        near(r.look[slot("cozy")], 0.2f, 1e-6f, "50/50 blend of cozy 40 and 0");
        near(r.look[slot("golden_hour")], 0.45f, 1e-6f, "50/50 blend of golden_hour 90 and 0");
        // 4 variation runs after 3 (on the clamped value): an offset range can push it back down
        BiomeTable tv = BiomeTable.parse(List.of("desert = golden_hour 90", "desert @noon = golden_hour +30", "desert ~ = golden_hour -20..-20"), "t");
        r = LookResolver.resolve(new LookResolver.Settings(), tv, List.of(new LookResolver.Sample("desert", 1f, LookResolver.Precip.NONE)), env(0, 64, 0, 6000), List.of(), NO_PROMPTS, false);
        near(r.look[slot("golden_hour")], 0.8f, 1e-5f, "clamp (1.2 -> 1) happens before variation (-0.2) -> 0.8");
        // 5 zones last, ascending priority; set = lerp toward the zone look, offset = add
        Zones.Zone z1 = zone("a", 0, 10, "horror 80", false, 5);
        Zones.Zone z2 = zone("b", 0, 10, "horror -50", true, 1);
        List<Zones.Zone> zs = new ArrayList<>(List.of(z1, z2));
        Zones.sort(zs);
        check(zs.get(0).name.equals("b"), "zones sorted by ascending priority");
        LookResolver.Env inside = env(0, 64, 0, 6000);
        r = LookResolver.resolve(s, t, List.of(new LookResolver.Sample("plains", 1f, LookResolver.Precip.RAIN)), inside, zs, NO_PROMPTS, true);
        for (String l : r.why) if (l.contains("zone")) System.out.println("  " + l);
        near(r.look[slot("horror")], 0.8f, 1e-6f, "prio 5 set zone applied after prio 1 offset zone (offset clamped at 0 first)");
        near(r.look[slot("cozy")], 0f, 1e-6f, "set zone: unnamed sliders -> 0 inside");
        LookResolver.Env edge = env(14, 64, 0, 6000);    // half the blend of zone a (r 10, blend 8)
        r = LookResolver.resolve(s, t, List.of(new LookResolver.Sample("plains", 1f, LookResolver.Precip.RAIN)), edge, List.of(z1), NO_PROMPTS, false);
        near(r.look[slot("horror")], 0.4f, 1e-6f, "zone weight 0.5: lerp half way");
        near(r.look[slot("cozy")], 0.2f, 0.02f, "zone weight 0.5: plains cozy ~40 halved");
        LookResolver.Settings off = new LookResolver.Settings();
        off.zones = false;
        r = LookResolver.resolve(off, t, List.of(new LookResolver.Sample("plains", 1f, LookResolver.Precip.RAIN)), inside, List.of(z1), NO_PROMPTS, false);
        near(r.look[slot("horror")], 0f, 0f, "zones layer off");
        LookResolver.Settings nb = new LookResolver.Settings();
        nb.biomes = false;
        r = LookResolver.resolve(nb, t, List.of(new LookResolver.Sample("desert", 1f, LookResolver.Precip.NONE)), noon, List.of(), NO_PROMPTS, false);
        near(r.look[slot("golden_hour")], 0.3f, 1e-6f, "biomes off: only the @noon offset (0 + 0.3)");
        LookResolver.Settings nt = new LookResolver.Settings();
        nt.timeWeather = false;
        r = LookResolver.resolve(nt, t, List.of(new LookResolver.Sample("desert", 1f, LookResolver.Precip.NONE)), noon, List.of(), NO_PROMPTS, false);
        near(r.look[slot("frozen")], 0f, 0f, "time & weather off");
        // everything clamped
        boolean inRange = true;
        for (float v : r.look) inRange &= v >= 0f && v <= 1f;
        check(inRange, "final look in 0..1");
    }

    private static Zones.Zone zone(String name, double x, double r, String look, boolean offset, int prio) {
        Zones.Zone z = new Zones.Zone();
        z.name = name; z.cx = x; z.cy = 64; z.cz = 0; z.radius = r; z.blend = 8; z.look = look; z.offset = offset; z.priority = prio;
        String err = z.compile();
        if (err != null) throw new IllegalStateException(err);
        return z;
    }

    private static void precipitationAndSky() {
        System.out.println("\n[weather rules + sky access]");
        BiomeTable t = BiomeTable.parse(List.of(
                "b = cozy 10", "b ~ = cozy 0..0",
                "b @rain = wet 60", "b @snow = snow 80", "b @thunder = stormy +40", "b @noon = vibrant +50"), "t");
        LookResolver.Env e = env(0, 64, 0, 6000);
        e.rain = 1f; e.thunder = 1f;
        float[] none = resolve(t, e, List.of(new LookResolver.Sample("b", 1f, LookResolver.Precip.NONE))).look;
        near(none[slot("wet")], 0f, 0f, "no precipitation: @rain ignored");
        near(none[slot("snow")], 0f, 0f, "no precipitation: @snow ignored");
        near(none[slot("stormy")], 0.4f, 1e-6f, "@thunder still applies in a desert");
        float[] rain = resolve(t, e, List.of(new LookResolver.Sample("b", 1f, LookResolver.Precip.RAIN))).look;
        near(rain[slot("wet")], 0.6f, 1e-6f, "rainy: @rain");
        near(rain[slot("snow")], 0f, 0f, "rainy: @snow ignored");
        float[] snow = resolve(t, e, List.of(new LookResolver.Sample("b", 1f, LookResolver.Precip.SNOW))).look;
        near(snow[slot("snow")], 0.8f, 1e-6f, "snowy: @snow");
        near(snow[slot("wet")], 0f, 0f, "snowy: @snow replaces @rain");
        BiomeTable t2 = BiomeTable.parse(List.of("c = cozy 10", "c @rain = wet 60"), "t");
        float[] snowNoLine = resolve(t2, e, List.of(new LookResolver.Sample("c", 1f, LookResolver.Precip.SNOW))).look;
        near(snowNoLine[slot("wet")], 0.6f, 1e-6f, "snowy without @snow: @rain applies");
        LookResolver.Env cave = env(0, 64, 0, 6000);
        cave.rain = 1f; cave.sky = 0.25f;
        float[] c = resolve(t, cave, List.of(new LookResolver.Sample("b", 1f, LookResolver.Precip.RAIN))).look;
        near(c[slot("wet")], 0.15f, 1e-6f, "sky access 0.25 scales rain (lerp to 0.6 by 0.25)");
        near(c[slot("vibrant")], 0.125f, 1e-6f, "sky access 0.25 scales noon");
        LookResolver.Env nether = env(0, 64, 0, 6000);
        nether.rain = 1f; nether.conditions = false;
        float[] n = resolve(t, nether, List.of(new LookResolver.Sample("b", 1f, LookResolver.Precip.RAIN))).look;
        near(n[slot("vibrant")] + n[slot("wet")], 0f, 0f, "Nether / End: no time or weather");
        near(n[slot("cozy")], 0.1f, 1e-6f, "Nether / End: biome base only");
    }

    // ------------------------------------------------------------ border crossing (grid + blend)

    private static void borderCrossing() {
        System.out.println("\n[desert -> plains border at x = 0, walking along z = 7]");
        BiomeTable t = BiomeTable.parse(List.of("desert = golden_hour 50, apocalyptic 40", "desert ~ = golden_hour 0..0",
                "plains = cozy 30, vibrant 25", "plains ~ = cozy 0..0"), "t");
        float x10 = Float.NaN, x90 = Float.NaN, maxStep = 0f, prev = -1f;
        StringBuilder row = new StringBuilder("  x:");
        StringBuilder val = new StringBuilder("  desert share:");
        for (double x = -60; x <= 60; x += 0.25) {
            Map<String, LookResolver.Sample> m = new HashMap<>();
            for (double[] g : LookMath.grid(x, 7)) {
                String id = g[0] < 0 ? "desert" : "plains";
                m.computeIfAbsent(id, k -> new LookResolver.Sample(k, 0f, LookResolver.Precip.RAIN)).weight += (float) g[2];
            }
            LookResolver.Result r = resolve(t, env(x, 64, 7, 6000), new ArrayList<>(m.values()));
            float share = r.biomes.getOrDefault("plains", 0f);
            if (prev >= 0) maxStep = Math.max(maxStep, Math.abs(share - prev));
            prev = share;
            if (Float.isNaN(x10) && share >= 0.1f) x10 = (float) x;
            if (Float.isNaN(x90) && share >= 0.9f) x90 = (float) x;
            if (Math.abs(x % 10) < 1e-9) {
                row.append(String.format(Locale.ROOT, " %5.0f", x));
                val.append(String.format(Locale.ROOT, " %5.2f", 1f - share));
            }
        }
        System.out.println(row);
        System.out.println(val);
        System.out.printf(Locale.ROOT, "  plains share 10%% at x=%.1f, 90%% at x=%.1f -> %.1f blocks; biggest change per 1/4 block %.3f%n",
                x10, x90, x90 - x10, maxStep);
        check(x90 - x10 >= 30 && x90 - x10 <= 70, "gradual over ~40-60 blocks");
        check(maxStep < 0.06f, "no pop when the sample grid shifts (before the 4 s fade smooths it further)");
    }

    // ------------------------------------------------------------ wet ease

    private static void wetEase() {
        System.out.println("\n[asymmetric wet ease, 20 ticks / s]");
        float[] cur = new float[Slots.N], tgt = new float[Slots.N];
        tgt[Slots.WET] = 1f;
        tgt[slot("stormy")] = 1f;
        float dt = 0.05f, tWet63 = -1, tStorm63 = -1;
        for (int k = 1; k <= 20 * 300; k++) {
            LookMath.easeLook(cur, tgt, dt, 4f, 30f, 120f, Slots.WET);
            float t = k * dt;
            if (tWet63 < 0 && cur[Slots.WET] >= 0.632f) tWet63 = t;
            if (tStorm63 < 0 && cur[slot("stormy")] >= 0.632f) tStorm63 = t;
        }
        tgt[Slots.WET] = 0f;
        float tDry37 = -1, tDry90 = -1;
        for (int k = 1; k <= 20 * 600; k++) {
            LookMath.easeLook(cur, tgt, dt, 4f, 30f, 120f, Slots.WET);
            float t = k * dt;
            if (tDry37 < 0 && cur[Slots.WET] <= 0.368f) tDry37 = t;
            if (tDry90 < 0 && cur[Slots.WET] <= 0.1f) tDry90 = t;
        }
        System.out.printf(Locale.ROOT, "  wet up to 63%% in %.1f s (want 30), other sliders in %.1f s (want 4); dries to 37%% in %.1f s (want 120), to 10%% in %.0f s%n",
                tWet63, tStorm63, tDry37, tDry90);
        check(Math.abs(tWet63 - 30f) < 0.5f, "wet rises with 30 s");
        check(Math.abs(tStorm63 - 4f) < 0.2f, "other sliders fade with 4 s");
        check(Math.abs(tDry37 - 120f) < 1f, "wet dries with 120 s");
        float[] c2 = {0.3f}, t2 = {0.9f};
        LookMath.easeLook(c2, t2, 0.05f, 0f, 30f, 120f, -1);
        near(c2[0], 0.9f, 0f, "fade 0 s = hard jump");
    }

    // ------------------------------------------------------------ performance

    private static void performance(Path biomes) throws Exception {
        System.out.println("\n[performance: one resolve = 3 biomes from 25 samples, 3 zones]");
        BiomeTable t = BiomeTable.parse(Files.readAllLines(biomes), "biomes.txt");
        List<Zones.Zone> zs = new ArrayList<>(List.of(zone("a", 0, 30, "golden_hour 50", false, 0),
                zone("b", 5, 30, "horror +20", true, 1), zone("c", 900, 30, "frozen 50", false, 2)));
        LookResolver.Env e = env(3, 64, 4, 11500);
        e.rain = 0.4f;
        List<LookResolver.Sample> s = new ArrayList<>();
        double[][] g = LookMath.grid(3, 4);
        String[] ids = {"desert", "plains", "savanna"};
        for (int i = 0; i < g.length; i++) {
            String id = ids[i % 3];
            LookResolver.Sample x = s.stream().filter(q -> q.id.equals(id)).findFirst().orElse(null);
            if (x == null) s.add(new LookResolver.Sample(id, (float) g[i][2], id.equals("plains") ? LookResolver.Precip.RAIN : LookResolver.Precip.NONE));
            else x.weight += (float) g[i][2];
        }
        LookResolver.Settings set = new LookResolver.Settings();
        for (int i = 0; i < 20000; i++) LookResolver.resolve(set, t, s, e, zs, NO_PROMPTS, false);   // warm up
        int n = 50000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            e.x = 3 + (i & 7);
            LookResolver.resolve(set, t, s, e, zs, NO_PROMPTS, false);
        }
        double us = (System.nanoTime() - t0) / 1e3 / n;
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) LookMath.grid(3 + (i & 7), 4);
        double gus = (System.nanoTime() - t1) / 1e3 / n;
        System.out.printf(Locale.ROOT, "  resolve %.1f us, grid %.2f us (budget 200 us; the 25 world.getBiome calls are not included)%n", us, gus);
        check(us < 200, "resolve under 0.2 ms");
    }
}
