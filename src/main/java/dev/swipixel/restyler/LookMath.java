package dev.swipixel.restyler;

import java.nio.charset.StandardCharsets;

/** World-look maths: condition weights, value noise, zone falloff, easing. Plain Java, no Minecraft. */
final class LookMath {

    private LookMath() {}

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    /** Distance between two times of day on the 24000-tick circle. */
    static float circDist(float t, float c) {
        float d = Math.abs(t - c) % 24000f;
        return Math.min(d, 24000f - d);
    }

    static float bell(float t, float c, float h) {
        float q = circDist(t, c) / h;
        float b = Math.max(0f, 1f - q * q);
        return b * b;
    }

    /** dawn, noon, dusk, night for t = timeOfDay % 24000 (0 = 06:00, 6000 = noon, 12000 = 18:00, 18000 = midnight). */
    static float[] timeWeights(float t) {
        t = ((t % 24000f) + 24000f) % 24000f;
        return new float[]{
                bell(t, 0f, 1500f),
                smoothstep(1000f, 3000f, t) * (1f - smoothstep(9000f, 11000f, t)),
                bell(t, 12000f, 1500f),
                smoothstep(13000f, 14500f, t) * (1f - smoothstep(22000f, 23500f, t))};
    }

    // ------------------------------------------------------------ noise

    /** FNV-1a 64 over "salt \0 biome \0 slot": the same spot gets the same seed every session. */
    static long hash(String salt, String biome, int slot) {
        long h = 0xcbf29ce484222325L;
        byte[] a = (salt + '\0' + biome + '\0' + slot).getBytes(StandardCharsets.UTF_8);
        for (byte b : a) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static long mix(long z) {          // splitmix64 finaliser
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** Lattice value in -1..1. */
    private static float lattice(long seed, long ix, long iz) {
        long h = mix(seed ^ mix(ix * 0x9E3779B97F4A7C15L ^ mix(iz + 0x632BE59BD9B4E019L)));
        return (float) ((h >>> 11) * 0x1.0p-53 * 2.0 - 1.0);
    }

    private static double quintic(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /** Smooth 2D value noise in -1..1 (quintic interpolation, C2-continuous). */
    static float valueNoise(double x, double z, long seed) {
        long ix = (long) Math.floor(x), iz = (long) Math.floor(z);
        double fx = quintic(x - ix), fz = quintic(z - iz);
        double a = lattice(seed, ix, iz), b = lattice(seed, ix + 1, iz);
        double c = lattice(seed, ix, iz + 1), d = lattice(seed, ix + 1, iz + 1);
        double ab = a + (b - a) * fx, cd = c + (d - c) * fx;
        return (float) (ab + (cd - ab) * fz);
    }

    // ------------------------------------------------------------ biome sampling

    /**
     * The 5 x 5 biome sample grid around (px, pz): world-aligned, 16 blocks apart (radius 32), so the points don't
     * move with the player; each weighted exp(-(d/24)^2) by its distance to the player. Returns {x, z, weight} x 25.
     */
    static double[][] grid(double px, double pz) {
        double[][] out = new double[25][];
        long gx = Math.round(px / 16.0) * 16, gz = Math.round(pz / 16.0) * 16;
        int n = 0;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                long sx = gx + 16L * i, sz = gz + 16L * j;
                double d = Math.hypot(sx + 0.5 - px, sz + 0.5 - pz) / 24.0;
                out[n++] = new double[]{sx, sz, Math.exp(-d * d)};
            }
        }
        return out;
    }

    // ------------------------------------------------------------ zones

    /** 1 inside (d <= 0), smoothstep down to 0 over blend blocks outside. */
    static float zoneWeight(double d, double blend) {
        if (d <= 0) return 1f;
        if (blend <= 0) return 0f;
        return 1f - smoothstep(0f, (float) blend, (float) d);
    }

    // ------------------------------------------------------------ easing

    /** Exponential ease toward target with time constant tc seconds (tc <= 0: jump). */
    static float ease(float cur, float target, float dt, float tc) {
        if (tc <= 0f) return target;
        return cur + (target - cur) * (1f - (float) Math.exp(-dt / tc));
    }

    /** Rises with time constant rise, falls with time constant fall. */
    static float easeAsym(float cur, float target, float dt, float rise, float fall) {
        return ease(cur, target, dt, target > cur ? rise : fall);
    }

    /** The world look's final ease: every slider with fade seconds, wet with its own rise / dry times. */
    static void easeLook(float[] cur, float[] target, float dt, float fade, float wetRise, float wetDry, int wetSlot) {
        for (int i = 0; i < cur.length; i++) {
            cur[i] = i == wetSlot ? easeAsym(cur[i], target[i], dt, wetRise, wetDry) : ease(cur[i], target[i], dt, fade);
        }
    }
}
