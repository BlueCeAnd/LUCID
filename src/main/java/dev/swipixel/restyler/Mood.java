package dev.swipixel.restyler;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Mood pass: a colour grade over the game, using the real depth for fog and the
 * view direction for the sky / aurora. Each mood has a slider 0..100 %; the parameters are blended
 * neutral + sum(w_i * (mood_i - neutral)), weights scaled down so they never sum above 1.
 * Slider changes fade in over ~0.6 s. Amounts are saved in config/restyler/moods.properties.
 */
final class Mood {

    // parameter layout (floats)
    static final int EXPOSURE = 0, TOE = 1, SAT = 2, SH_TINT = 3, HI_TINT = 6, FOG_COL = 9, FOG_DIST = 12,
            FOG_AMT = 13, SKY_TOP = 14, SKY_HOR = 17, SKY_MIX = 20, AURORA = 21, AUR_C1 = 22, AUR_C2 = 25,
            AUR_SPILL = 28, GLOW = 29, VIGNETTE = 30, CONTRAST = 31, N = 32;

    static final String[] NAMES = {"Dark Fantasy", "Aurora Night", "Gothic", "Apocalyptic", "Horror",
            "Golden Hour", "Cozy", "Dreamy", "Cinematic", "Vibrant", "Misty Morning", "Neon Night", "Frozen", "Stormy"};
    static final String[] KEYS = {"dark_fantasy", "aurora_night", "gothic", "apocalyptic", "horror",
            "golden_hour", "cozy", "dreamy", "cinematic", "vibrant", "misty_morning", "neon_night", "frozen", "stormy"};

    private static final float[] NEUTRAL = p(1f, 0f, 1f, 1, 1, 1, 1, 1, 1, 0, 0, 0, 40f, 0f, 0, 0, 0, 0, 0, 0, 0f,
            0f, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 1f);

    // exposure, shadow crush, saturation, shadow tint, highlight tint, fog colour, fog distance (blocks), fog amount,
    // sky top, sky horizon, sky mix, aurora, aurora colour 1 (top), aurora colour 2 (bottom), aurora spill, glow,
    // vignette, contrast            (the values of the offline preview you liked)
    private static final float[][] PRESETS = {
            p(0.85f, 0.35f, 0.75f, 0.75f, 0.85f, 1.15f, 1.05f, 1.0f, 0.9f, 0.10f, 0.13f, 0.18f, 40f, 0.75f,
                    0.03f, 0.04f, 0.08f, 0.20f, 0.22f, 0.30f, 0.9f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.6f, 0.45f, 1.15f),
            p(0.5f, 0.3f, 0.8f, 0.7f, 0.9f, 1.2f, 0.85f, 1.1f, 1.0f, 0.02f, 0.05f, 0.06f, 70f, 0.6f,
                    0.005f, 0.01f, 0.03f, 0.02f, 0.05f, 0.07f, 1.0f, 1.0f, 0.15f, 1.0f, 0.45f, 0.55f, 0.2f, 0.9f,
                    0.35f, 0.8f, 0.4f, 1.1f),
            p(0.8f, 0.45f, 0.45f, 0.85f, 0.85f, 1.05f, 1.1f, 0.95f, 0.8f, 0.12f, 0.12f, 0.14f, 30f, 0.8f,
                    0.10f, 0.10f, 0.12f, 0.30f, 0.28f, 0.28f, 0.95f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.5f, 0.55f, 1.3f),
            p(0.9f, 0.3f, 0.55f, 1.05f, 0.9f, 0.75f, 1.25f, 0.9f, 0.6f, 0.55f, 0.32f, 0.18f, 25f, 0.85f,
                    0.35f, 0.12f, 0.05f, 0.85f, 0.42f, 0.18f, 1.0f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.4f, 0.4f, 1.15f),
            p(0.6f, 0.55f, 0.35f, 0.9f, 1.05f, 0.85f, 1.0f, 1.05f, 0.85f, 0.05f, 0.06f, 0.05f, 12f, 0.92f,
                    0.02f, 0.02f, 0.02f, 0.08f, 0.09f, 0.08f, 1.0f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.3f, 0.7f, 1.25f),
            // the looks players pick most in shader packs and colour grading (BSL / Sildur sunsets, Mellow,
            // Solas, Photon / Bliss, Sildur's Vibrant, Rethinking Voxels, neon grades, winter, Derivative rain)
            // golden hour: low warm sun, violet shadows, orange horizon, warm haze
            p(1.05f, 0.1f, 1.15f, 0.95f, 0.88f, 0.95f, 1.18f, 1.0f, 0.78f, 0.9f, 0.55f, 0.3f, 120f, 0.45f,
                    0.18f, 0.2f, 0.45f, 1.0f, 0.55f, 0.25f, 0.75f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.5f, 0.3f, 1.08f),
            // cozy: soft warm light, gentle haze, a little faded (cottagecore)
            p(1.02f, 0.05f, 1.0f, 1.02f, 0.9f, 0.8f, 1.16f, 1.0f, 0.8f, 0.75f, 0.55f, 0.35f, 90f, 0.3f,
                    0.35f, 0.45f, 0.65f, 0.95f, 0.8f, 0.6f, 0.45f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.6f, 0.35f, 0.95f),
            // dreamy: bright, soft, glowing, pink-lavender haze and sky
            p(1.12f, 0f, 0.85f, 0.95f, 0.9f, 1.1f, 1.08f, 0.98f, 1.05f, 0.85f, 0.75f, 0.95f, 70f, 0.45f,
                    0.55f, 0.6f, 1.0f, 1.0f, 0.75f, 0.9f, 0.7f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.9f, 0.2f, 0.88f),
            // cinematic: teal shadows, warm highlights, contrast, vignette (the Hollywood grade)
            p(0.98f, 0.2f, 1.05f, 0.85f, 1.0f, 1.12f, 1.12f, 1.0f, 0.85f, 0.45f, 0.55f, 0.65f, 160f, 0.25f,
                    0.15f, 0.3f, 0.55f, 0.6f, 0.7f, 0.8f, 0.35f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.35f, 0.45f, 1.2f),
            // vibrant: punchy saturated colour, deep blue sky, clear air
            p(1.05f, 0.1f, 1.45f, 0.95f, 0.98f, 1.05f, 1.05f, 1.02f, 0.97f, 0.5f, 0.7f, 1.0f, 220f, 0.12f,
                    0.12f, 0.3f, 0.95f, 0.55f, 0.75f, 1.0f, 0.5f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.3f, 0.1f, 1.15f),
            // misty morning: thick soft fog close by, pale and quiet
            p(1.0f, 0f, 0.75f, 0.92f, 0.97f, 1.05f, 1.05f, 1.03f, 0.98f, 0.75f, 0.8f, 0.85f, 22f, 0.8f,
                    0.5f, 0.58f, 0.68f, 0.85f, 0.86f, 0.86f, 0.8f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.4f, 0.15f, 0.9f),
            // neon night: dark magenta / cyan, glowing, neon curtains in the sky
            p(0.7f, 0.4f, 1.35f, 0.8f, 0.75f, 1.25f, 1.2f, 0.85f, 1.15f, 0.35f, 0.1f, 0.45f, 45f, 0.55f,
                    0.02f, 0f, 0.08f, 0.35f, 0.05f, 0.4f, 0.95f, 0.25f, 0f, 0.9f, 1.0f, 1.0f, 0.1f, 0.8f,
                    0.3f, 0.9f, 0.45f, 1.2f),
            // frozen: cold blue, pale, crisp air
            p(1.04f, 0.1f, 0.6f, 0.78f, 0.92f, 1.22f, 0.94f, 1.02f, 1.14f, 0.65f, 0.8f, 1.0f, 60f, 0.4f,
                    0.35f, 0.55f, 0.85f, 0.85f, 0.92f, 1.0f, 0.6f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.5f, 0.25f, 1.05f),
            // stormy: dark grey-blue sky, grey fog, heavy contrast
            p(0.75f, 0.35f, 0.6f, 0.88f, 0.92f, 1.05f, 0.95f, 0.97f, 1.02f, 0.25f, 0.28f, 0.32f, 50f, 0.6f,
                    0.08f, 0.09f, 0.11f, 0.22f, 0.24f, 0.27f, 0.9f, 0f, 0, 0, 0, 0, 0, 0, 0f, 0.2f, 0.5f, 1.25f),
    };

    private static final float[] target = new float[NAMES.length];
    private static final float[] shown = new float[NAMES.length];
    private static final float[] params = new float[N];
    private static long lastNs = 0;
    private static boolean loaded = false;

    // always-on extras (also without a mood): brightness 0.25..2 (1 = unchanged), torch bloom 0..1, sun/moon rays 0..1
    private static float brightness = 1f, bloom = 0.5f, rays = 0.5f, torchRays = 0.35f;
    private static boolean darkNights = true;     // nights look like the game's 0 % brightness

    static boolean darkNights() { load(); return darkNights; }
    static void setDarkNights(boolean v) { load(); darkNights = v; save(); }

    static float brightness() { load(); return brightness; }
    static float bloom() { load(); return bloom; }
    static float rays() { load(); return rays; }
    static float torchRays() { load(); return torchRays; }
    static void setBrightness(float v) { load(); brightness = Math.max(0.25f, Math.min(2f, v)); save(); }
    static void setBloom(float v) { load(); bloom = Math.max(0f, Math.min(1f, v)); save(); }
    static void setRays(float v) { load(); rays = Math.max(0f, Math.min(1f, v)); save(); }
    static void setTorchRays(float v) { load(); torchRays = Math.max(0f, Math.min(1f, v)); save(); }

    /** Extras alone also need the pass. */
    private static boolean extras() {
        return Math.abs(brightness - 1f) > 1e-3f || bloom > 0f || rays > 0f || torchRays > 0f || darkNights;
    }

    private Mood() {}

    private static float[] p(float... v) {
        if (v.length != N) throw new IllegalArgumentException("mood preset needs " + N + " values, got " + v.length);
        return v;
    }

    // World looks: while unpinned, WorldLook drives the sliders through this auto slice (never saved).
    private static final float[] auto = new float[NAMES.length];
    private static boolean autoOn = false;

    /** null: the saved manual values are used (as before). Otherwise the world look's values, not saved. */
    static void setAuto(float[] slice) {
        load();
        autoOn = slice != null;
        if (autoOn) for (int i = 0; i < auto.length; i++) auto[i] = Math.max(0f, Math.min(1f, slice[i]));
    }

    /** The value the look fades toward: the auto value while world looks drive it, else the slider. */
    private static float want(int i) { return autoOn ? auto[i] : target[i]; }

    /** What the menus show (auto values while world looks drive the look). */
    static float get(int i) { load(); return want(i); }

    /** The saved manual slider. */
    static float manual(int i) { load(); return target[i]; }

    /** Copies values into the saved manual sliders without pinning (WorldLook's "copy the auto look"). */
    static void setManual(float[] v, int off) {
        load();
        for (int i = 0; i < target.length; i++) target[i] = Math.max(0f, Math.min(1f, v[off + i]));
        save();
    }

    static void set(int i, float v) {
        WorldLook.INSTANCE.beforeManualChange();
        load();
        target[i] = Math.max(0f, Math.min(1f, v));
        save();
    }

    static void allOff() {
        WorldLook.INSTANCE.beforeManualChange();
        load();
        java.util.Arrays.fill(target, 0f);
        save();
    }

    static int index(String key) {
        String k = key.toLowerCase(Locale.ROOT).replace(' ', '_').replace("-", "_");
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(k) || KEYS[i].startsWith(k)) return i;
        }
        return -1;
    }

    /** Any mood set or still fading out (no timing side effects): the depth passes are needed. */
    static boolean wanted() {
        load();
        if (extras()) return true;
        for (int i = 0; i < target.length; i++) {
            if (want(i) > 0f || shown[i] > 1e-3f) return true;
        }
        return false;
    }

    /** Fades the shown amounts toward the sliders; true if any mood is visible. */
    static boolean active() {
        load();
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0f : Math.min(0.25f, (now - lastNs) / 1e9f);
        lastNs = now;
        float k = 1f - (float) Math.exp(-dt / 0.2f);       // ~0.6 s to arrive
        boolean any = false;
        for (int i = 0; i < shown.length; i++) {
            float t = want(i);
            shown[i] += (t - shown[i]) * k;
            if (Math.abs(t - shown[i]) < 1e-3f) shown[i] = t;
            any |= shown[i] > 1e-3f;
        }
        return any || extras();
    }

    /** Blended parameters for the current (faded) amounts; [N] is the total weight (emitter keep-alive). */
    static float[] params() {
        float sum = 0f;
        for (float s : shown) sum += s;
        float scale = sum > 1f ? 1f / sum : 1f;
        for (int j = 0; j < N; j++) {
            float v = NEUTRAL[j];
            for (int i = 0; i < shown.length; i++) {
                v += shown[i] * scale * (PRESETS[i][j] - NEUTRAL[j]);
            }
            params[j] = v;
        }
        // fog distance blends in 1/distance (density), not in distance
        float dens = 0f, w = 0f;
        for (int i = 0; i < shown.length; i++) {
            if (PRESETS[i][FOG_AMT] > 0) { dens += shown[i] * scale / PRESETS[i][FOG_DIST]; w += shown[i] * scale; }
        }
        params[FOG_DIST] = w > 0 ? w / dens : NEUTRAL[FOG_DIST];
        return params;
    }

    static float totalWeight() {
        float sum = 0f;
        for (float s : shown) sum += s;
        return Math.min(1f, sum);
    }

    static String summary() {
        load();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            if (want(i) > 0) sb.append(sb.length() > 0 ? ", " : "").append(NAMES[i]).append(' ')
                    .append(Math.round(want(i) * 100)).append('%');
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("restyler").resolve("moods.properties");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.exists(f)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f)) {
            p.load(r);
            for (int i = 0; i < KEYS.length; i++) {
                target[i] = Math.max(0f, Math.min(1f, Float.parseFloat(p.getProperty(KEYS[i], "0"))));
                shown[i] = target[i];
            }
            brightness = Math.max(0.25f, Math.min(2f, Float.parseFloat(p.getProperty("brightness", "1"))));
            bloom = Math.max(0f, Math.min(1f, Float.parseFloat(p.getProperty("torch_bloom", "0.5"))));
            rays = Math.max(0f, Math.min(1f, Float.parseFloat(p.getProperty("sun_rays", "0.5"))));
            torchRays = Math.max(0f, Math.min(1f, Float.parseFloat(p.getProperty("torch_rays", "0.35"))));
            darkNights = Boolean.parseBoolean(p.getProperty("dark_nights", "true"));
        } catch (Exception e) {
            Restyler.LOG.warn("Restyler: could not read {}", f, e);
        }
    }

    private static void save() {
        Properties p = new Properties();
        for (int i = 0; i < KEYS.length; i++) p.setProperty(KEYS[i], Float.toString(target[i]));
        p.setProperty("brightness", Float.toString(brightness));
        p.setProperty("torch_bloom", Float.toString(bloom));
        p.setProperty("sun_rays", Float.toString(rays));
        p.setProperty("torch_rays", Float.toString(torchRays));
        p.setProperty("dark_nights", Boolean.toString(darkNights));
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file())) {
                p.store(w, "Restyler moods, 0..1 each");
            }
        } catch (Exception e) {
            Restyler.LOG.warn("Restyler: could not save {}", file(), e);
        }
    }
}
