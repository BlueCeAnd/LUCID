package dev.swipixel.restyler;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Style pass: art styles drawn after the effects and the mood (anime, pastel, oil paint, ...), using the real depth for
 * outlines. The first LOOKS entries mix (weights scaled down so they never sum above 1); Pixelate and CRT stack on
 * top of any look. Slider changes fade in over ~0.6 s. Saved in config/restyler/styles.properties.
 */
final class Style {

    static final String[] NAMES = {"Anime", "Pastel", "Oil paint", "Watercolor", "Comic", "Pencil sketch", "Noir",
            "Old film", "Vaporwave", "Retro green", "Pixelate", "CRT / VHS"};
    static final String[] KEYS = {"anime", "pastel", "oil_paint", "watercolor", "comic", "sketch", "noir", "old_film",
            "vaporwave", "retro_green", "pixelate", "crt"};
    static final String[] UNIFORMS = {"uAnime", "uPastel", "uOil", "uWater", "uComic", "uSketch", "uNoir", "uFilm",
            "uVapor", "uRetro", "uPixel", "uCrt"};
    static final int LOOKS = 10, PIXEL = 10, CRT = 11;

    private static final float[] target = new float[NAMES.length];
    private static final float[] shown = new float[NAMES.length];
    private static final float[] out = new float[NAMES.length];
    private static long lastNs = 0;
    private static boolean loaded = false;

    private Style() {}

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

    /** Any style set or still fading out (no timing side effects): the depth passes are needed. */
    static boolean wanted() {
        load();
        for (int i = 0; i < target.length; i++) {
            if (want(i) > 0f || shown[i] > 1e-3f) return true;
        }
        return false;
    }

    /** Fades the shown amounts toward the sliders; true if any style is visible. */
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

    /** Uniform values in UNIFORMS order: looks normalised to a total of at most 1, pixelate / CRT as set. */
    static float[] amounts() {
        float sum = 0f;
        for (int i = 0; i < LOOKS; i++) sum += shown[i];
        float scale = sum > 1f ? 1f / sum : 1f;
        for (int i = 0; i < shown.length; i++) {
            float v = i < LOOKS ? shown[i] * scale : shown[i];
            out[i] = v > 1e-3f ? v : 0f;
        }
        return out;
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
        return FabricLoader.getInstance().getConfigDir().resolve("restyler").resolve("styles.properties");
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
                p.store(w, "Restyler art styles, 0..1 each");
            }
        } catch (Exception e) {
            Restyler.LOG.warn("Restyler: could not save {}", file(), e);
        }
    }
}
