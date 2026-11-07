package dev.swipixel.restyler;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Effects: the world itself, rebuilt from the depth buffer. Wet (puddle reflections), snow cover, overgrown ruins and
 * realistic light run before the mood; depth of field and tilt-shift after it. Each one is independent (no mixing
 * limit). Slider changes fade in over ~0.6 s. Saved in config/restyler/effects.properties.
 */
final class Effect {

    static final String[] NAMES = {"Wet / puddles", "Snow cover", "Overgrown ruins", "Realistic light",
            "Depth of field", "Tilt-shift"};
    static final String[] KEYS = {"wet", "snow", "overgrown", "realistic", "depth_of_field", "tilt_shift"};
    static final int WET = 0, SNOW = 1, MOSS = 2, REAL = 3, DOF = 4, TILT = 5;

    private static final float[] target = new float[NAMES.length];
    private static final float[] shown = new float[NAMES.length];
    private static final float[] out = new float[NAMES.length];
    private static long lastNs = 0;
    private static boolean loaded = false;

    private Effect() {}

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
            if (KEYS[i].equals(k)) return i;
        }
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].startsWith(k)) return i;
        }
        return -1;
    }

    /** Any effect set or still fading out (no timing side effects): the depth passes are needed. */
    static boolean wanted() {
        load();
        for (int i = 0; i < target.length; i++) {
            if (want(i) > 0f || shown[i] > 1e-3f) return true;
        }
        return false;
    }

    /** Fades the shown amounts toward the sliders; true if any effect is visible. */
    static boolean active() {
        load();
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0f : Math.min(0.25f, (now - lastNs) / 1e9f);
        lastNs = now;
        float k = 1f - (float) Math.exp(-dt / 0.2f);
        boolean any = false;
        for (int i = 0; i < shown.length; i++) {
            float t = want(i);
            shown[i] += (t - shown[i]) * k;
            if (Math.abs(t - shown[i]) < 1e-3f) shown[i] = t;
            any |= shown[i] > 1e-3f;
        }
        return any;
    }

    /** Shown (faded) amounts in KEYS order. */
    static float[] amounts() {
        for (int i = 0; i < shown.length; i++) out[i] = shown[i] > 1e-3f ? shown[i] : 0f;
        return out;
    }

    static boolean world() { return out[WET] > 0f || out[SNOW] > 0f || out[MOSS] > 0f || out[REAL] > 0f; }

    static boolean lens() { return out[DOF] > 0f || out[TILT] > 0f; }

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
        return FabricLoader.getInstance().getConfigDir().resolve("restyler").resolve("effects.properties");
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
        } catch (Exception e) {
            Restyler.LOG.warn("Restyler: could not read {}", f, e);
        }
    }

    private static void save() {
        Properties p = new Properties();
        for (int i = 0; i < KEYS.length; i++) p.setProperty(KEYS[i], Float.toString(target[i]));
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file())) {
                p.store(w, "Restyler effects, 0..1 each");
            }
        } catch (Exception e) {
            Restyler.LOG.warn("Restyler: could not save {}", file(), e);
        }
    }
}
