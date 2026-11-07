package dev.swipixel.restyler;

import java.util.Locale;

/**
 * The 32 sliders of a look, in Vibe's order: 14 moods, 12 styles, 6 effects (values 0..1).
 * Plain Java (no Minecraft), shared by the world-look parser, resolver and commands.
 */
final class Slots {

    static final int NM = Mood.KEYS.length, NS = Style.KEYS.length, NE = Effect.KEYS.length;
    static final int N = NM + NS + NE;
    static final int WET = NM + NS + Effect.WET;
    static final String[] KEYS = new String[N];

    static {
        System.arraycopy(Mood.KEYS, 0, KEYS, 0, NM);
        System.arraycopy(Style.KEYS, 0, KEYS, NM, NS);
        System.arraycopy(Effect.KEYS, 0, KEYS, NM + NS, NE);
    }

    private Slots() {}

    /** Exact slider name ("golden_hour", "wet"), -1 if unknown. "-" and spaces count as "_". */
    static int index(String name) {
        String k = name.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (int i = 0; i < N; i++) if (KEYS[i].equals(k)) return i;
        return -1;
    }

    /** "golden_hour 50, apocalyptic 40" for values >= 1 %. */
    static String summary(float[] v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < N; i++) {
            int pct = Math.round(v[i] * 100);
            if (pct >= 1) b.append(b.length() > 0 ? ", " : "").append(KEYS[i]).append(' ').append(pct);
        }
        return b.length() == 0 ? "nothing" : b.toString();
    }

    /** "golden_hour +12, vibrant -5" for changes of at least 1 %. */
    static String signed(float[] d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < N; i++) {
            int pct = Math.round(d[i] * 100);
            if (pct != 0) b.append(b.length() > 0 ? ", " : "").append(KEYS[i]).append(pct > 0 ? " +" : " ").append(pct);
        }
        return b.length() == 0 ? "no change" : b.toString();
    }

    static void clamp(float[] v) {
        for (int i = 0; i < v.length; i++) v[i] = v[i] < 0f ? 0f : (v[i] > 1f ? 1f : v[i]);
    }
}
