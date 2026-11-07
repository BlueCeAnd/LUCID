package dev.swipixel.restyler;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /vibe <words>: words -> mood, art style and effect sliders.
 *
 * The taste lives in a table (vibes.txt: "blade runner = neon_night 90, wet 70, ..."), written by hand. CLIP's text
 * encoder (LUCID Spectacular's clip_text.onnx + tokenizer, from config/restyler/clip/ or config/snowfilter/clip/) is
 * only a ruler of MEANING: it places the typed words and every table line on its map, and the closest lines are
 * combined: the closest line first, then whatever meaning is left ("horror anime" = anime + horror). So "emerald"
 * lands on diamond / jungle / gemstone even though it is not in the table. Multi-idea texts ("naruto in a thunderstorm") are also looked up part by part.
 * Table embeddings are cached in config/restyler/vibes_cache.bin, so only new lines cost time at start.
 * Without CLIP, only exact table lines work.
 */
final class Vibe {

    private static final String TEMPLATE = "a minecraft screenshot, {}";
    private static final int MAX_PARTS = 4;         // table lines one text can be made of
    private static final float MIN_PART = 0.12f;    // what is left of the text must match a line at least this well
    private static final float SURE0 = 0.20f, SURE1 = 0.40f;   // closeness (after centring) from "unsure" to "sure"

    private static final int NM = Mood.NAMES.length, NS = Style.NAMES.length, NE = Effect.NAMES.length;

    private static List<String> texts = new ArrayList<>();
    private static List<float[]> settings = new ArrayList<>();   // per line: NM + NS + NE values 0..1
    private static volatile OrtSession clip;
    private static volatile ClipTokenizer tok;
    private static float[][] emb;                    // per line, centred + normalized
    private static float[] mu;
    private static volatile String status = "CLIP not loaded yet";
    private static volatile boolean loading = false;
    private static volatile boolean clipMissing = false;   // no CLIP files, or CLIP failed to load
    private static volatile int tableHash = 0;
    private static final Object CACHE_LOCK = new Object();
    private static Map<String, float[]> lookCache;        // world-look prompt results ("look|<table>|text" in the cache file)

    private Vibe() {}

    static String status() { return status; }

    /** /vibe world reload: look for CLIP again next time a prompt needs it (the player may have added it). */
    static void retryClip() { if (clip == null && !loading) clipMissing = false; }

    // ------------------------------------------------------------ table

    /** config/restyler/vibes.txt if the player made one, else the copy inside the mod. */
    private static synchronized void loadTable() throws Exception {
        Map<String, Integer> slot = new HashMap<>();
        for (int i = 0; i < NM; i++) slot.put(Mood.KEYS[i], i);
        for (int i = 0; i < NS; i++) slot.put(Style.KEYS[i], NM + i);
        for (int i = 0; i < NE; i++) slot.put(Effect.KEYS[i], NM + NS + i);
        Path own = FabricLoader.getInstance().getConfigDir().resolve("restyler").resolve("vibes.txt");
        List<String> lines;
        if (Files.exists(own)) {
            lines = Files.readAllLines(own, StandardCharsets.UTF_8);
        } else {
            try (InputStream in = Vibe.class.getResourceAsStream("/assets/restyler/vibes.txt");
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                lines = r.lines().toList();
            }
        }
        List<String> t = new ArrayList<>();
        List<float[]> s = new ArrayList<>();
        for (String line : lines) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
            String name = line.substring(0, line.indexOf('=')).strip().toLowerCase(Locale.ROOT);
            float[] v = new float[NM + NS + NE];
            for (String kv : line.substring(line.indexOf('=') + 1).split(",")) {
                String[] p = kv.strip().split("\\s+");
                if (p.length != 2 || !slot.containsKey(p[0])) continue;
                v[slot.get(p[0])] = Math.max(0f, Math.min(1f, Float.parseFloat(p[1]) / 100f));
            }
            t.add(name);
            s.add(v);
        }
        int h = t.hashCode();
        for (float[] v : s) h = 31 * h + Arrays.hashCode(v);
        tableHash = h;
        texts = t;
        settings = s;
    }

    // ------------------------------------------------------------ CLIP

    /** Loads the table + CLIP in the background (once), then runs onReady on the game thread. */
    static synchronized void ensureLoaded(Runnable onReady) {
        if (clip != null) { onReady.run(); return; }
        if (loading) { onReady.run(); return; }      // still loading: exact table lines work meanwhile
        try {
            if (texts.isEmpty()) loadTable();
        } catch (Exception e) {
            status = "vibes.txt could not be read: " + e;
        }
        Path dir = null;
        Path cfg = FabricLoader.getInstance().getConfigDir();
        for (Path d : new Path[]{cfg.resolve("restyler").resolve("clip"), cfg.resolve("snowfilter").resolve("clip")}) {
            if (Files.exists(d.resolve("clip_text.onnx")) && ClipTokenizer.present(d)) { dir = d; break; }
        }
        if (texts.isEmpty()) {
            onReady.run();
            return;
        }
        loading = true;
        final Path clipDir = dir == null ? cfg.resolve("restyler").resolve("clip") : dir;
        final boolean download = dir == null;
        status = download ? "installing bundled CLIP (first use only)" :
                "loading CLIP from " + dir + " (first time: the table is measured once, then cached)";
        Thread t = new Thread(() -> {
            try {
                long t0 = System.nanoTime();
                if (download) {
                    MinecraftClient.getInstance().execute(() -> Restyler.say("Restyler: installing the bundled CLIP (first use only)", true));
                    ClipFetcher.download(clipDir, message -> MinecraftClient.getInstance().execute(() -> Restyler.say(message, true)));
                }
                OrtEnvironment env = OrtEnvironment.getEnvironment();
                ClipTokenizer k = ClipTokenizer.load(clipDir);
                OrtSession s = env.createSession(clipDir.resolve("clip_text.onnx").toString(), new OrtSession.SessionOptions());
                Path cacheFile = cfg.resolve("restyler").resolve("vibes_cache.bin");
                Map<String, float[]> cache = readCache(cacheFile);
                int fresh = 0;
                float[][] raw = new float[texts.size()][];
                int lastPct = -1;
                for (int i = 0; i < raw.length; i++) {
                    String key = TEMPLATE.replace("{}", texts.get(i));
                    float[] v = cache.get(key);
                    if (v == null) {
                        v = normalize(run(env, s, k, key));
                        cache.put(key, v);
                        fresh++;
                    }
                    raw[i] = v;
                    int pct = (i + 1) * 100 / raw.length;
                    if (fresh > 0 && pct / 5 != lastPct / 5) {          // progress above the hotbar, every 5 %
                        lastPct = pct;
                        final int p = pct;
                        MinecraftClient.getInstance().execute(() ->
                                Restyler.say("Generating with CLIP: reading the vibe table " + p + "% (first time only)", true));
                    }
                }
                if (fresh > 0) synchronized (CACHE_LOCK) {
                    Map<String, float[]> now = readCache(cacheFile);        // keep world-look entries written meanwhile
                    now.putAll(cache);
                    writeCache(cacheFile, now);
                }
                float[] m = new float[raw[0].length];
                for (float[] r : raw) for (int d = 0; d < m.length; d++) m[d] += r[d] / raw.length;
                float[][] e = new float[raw.length][];
                for (int i = 0; i < raw.length; i++) e[i] = centred(raw[i], m);
                mu = m;
                emb = e;
                tok = k;
                clip = s;
                status = String.format(Locale.ROOT, "CLIP ready in %.1f s, %d table lines (%d new)",
                        (System.nanoTime() - t0) / 1e9, texts.size(), fresh);
            } catch (Throwable ex) {
                clipMissing = true;
                status = "CLIP failed: " + ex;
                if (download) Restyler.LOG.warn("Restyler: CLIP download or load failed; check the connection and retry", ex);
                else Restyler.LOG.warn("Restyler: CLIP could not load", ex);
            } finally {
                loading = false;
                MinecraftClient.getInstance().execute(onReady);
            }
        }, "restyler-clip");
        t.setDaemon(true);
        t.start();
    }

    private static Map<String, float[]> readCache(Path f) {
        Map<String, float[]> m = new HashMap<>();
        if (!Files.exists(f)) return m;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String key = in.readUTF();
                float[] v = new float[in.readInt()];
                for (int d = 0; d < v.length; d++) v[d] = in.readFloat();
                m.put(key, v);
            }
        } catch (Exception e) {
            Restyler.LOG.warn("Restyler: vibe cache unreadable, measuring again", e);
            m.clear();
        }
        return m;
    }

    private static void writeCache(Path f, Map<String, float[]> m) {
        try {
            Files.createDirectories(f.getParent());
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(f))) {
                out.writeInt(m.size());
                for (Map.Entry<String, float[]> e : m.entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeInt(e.getValue().length);
                    for (float x : e.getValue()) out.writeFloat(x);
                }
            }
        } catch (Exception e) {
            Restyler.LOG.warn("Restyler: could not save the vibe cache", e);
        }
    }

    // ------------------------------------------------------------ lookup

    /**
     * The text as a mix of table lines: take the closest line, remove that meaning from the text, take the line closest
     * to what is left, and so on ("horror anime" -> anime, then horror). Lines that only repeat a meaning already taken
     * (manga after anime) find nothing left and drop out. Each line counts by how much of the text it explained.
     */
    private static float[] lookup(String text, List<String> near) throws Exception {
        float[] q = centred(normalize(run(OrtEnvironment.getEnvironment(), clip, tok, TEMPLATE.replace("{}", text))), mu);
        float[] res = q.clone();
        float[] out = new float[NM + NS + NE];
        float first = 0f;
        boolean[] used = new boolean[emb.length];
        for (int pick = 0; pick < MAX_PARTS; pick++) {
            int bi = -1;
            float bc = -1f;
            for (int i = 0; i < emb.length; i++) {
                if (used[i]) continue;
                float c = dot(res, emb[i]);
                if (c > bc) { bc = c; bi = i; }
            }
            if (bi < 0 || (pick > 0 && bc < MIN_PART)) break;
            used[bi] = true;
            if (pick == 0) first = bc;
            float w = (float) Math.sqrt(Math.max(bc, 0f) / Math.max(first, 1e-4f));   // share vs the main meaning
            for (int j = 0; j < out.length; j++) out[j] = Math.max(out[j], settings.get(bi)[j] * w);
            for (int d = 0; d < res.length; d++) res[d] -= bc * emb[bi][d];
            if (near != null) near.add(texts.get(bi) + String.format(Locale.ROOT, " %.2f", bc));
        }
        float sure = Math.max(0f, Math.min(1f, (first - SURE0) / (SURE1 - SURE0)));
        for (int j = 0; j < out.length; j++) out[j] *= sure;
        return out;
    }

    /** Text -> sliders. Returns a one-line summary for chat. Call from a background thread (CLIP takes ~50 ms). */
    static String apply(String text) throws Exception {
        String[] detailOut = new String[1];
        float[] v = compute(text.strip().toLowerCase(Locale.ROOT), detailOut);
        String detail = detailOut[0];
        final float[] fv = v;
        MinecraftClient.getInstance().execute(() -> {
            for (int i = 0; i < NM; i++) Mood.set(i, fv[i]);
            for (int i = 0; i < NS; i++) Style.set(i, fv[NM + i]);
            for (int i = 0; i < NE; i++) Effect.set(i, fv[NM + NS + i]);
        });
        return "Moods: " + summary(Mood.NAMES, v, 0, NM) + " | Styles: " + summary(Style.NAMES, v, NM, NS)
                + " | Effects: " + summary(Effect.NAMES, v, NM + NS, NE) + "   (" + detail + ")";
    }

    /** Text (lower case) -> the 32 slider values, as /vibe would set them. detail[0] says how it was found. */
    private static float[] compute(String t, String[] detailOut) throws Exception {
        float[] v = new float[NM + NS + NE];
        String detail;
        int exact = texts.indexOf(t);
        if (exact >= 0) {                                   // a line of the table, word for word: use it as written
            v = settings.get(exact).clone();
            detail = "table line";
        } else if (clip != null) {
            List<String> near = new ArrayList<>();
            v = lookup(t, near);
            // several ideas ("naruto in a thunderstorm"): each part looked up too, each slider keeps its strongest
            String[] parts = t.split("\\s*(?:,|;|\\+|\\band\\b|\\bwith\\b|\\bin\\b)\\s*");
            if (parts.length > 1) {
                for (String p : parts) {
                    if (p.isBlank()) continue;
                    List<String> pn = new ArrayList<>();
                    float[] pv = lookup(p.strip(), pn);
                    for (int j = 0; j < v.length; j++) v[j] = Math.max(v[j], 0.9f * pv[j]);
                    near.add("[" + p.strip() + ": " + pn.get(0) + "]");
                }
            }
            detail = "closest: " + String.join(", ", near);
        } else {
            detail = "not in the table, and " + status;
        }
        detailOut[0] = detail;
        return v;
    }

    // ------------------------------------------------------------ world looks

    /** resolveLook: CLIP is still loading (or was just asked to load); ask again later. */
    static final float[] RETRY = new float[0];

    /**
     * A world-look prompt -> 32 slider values, without touching the sliders. Exact table line, else the cached
     * result, else CLIP. Returns RETRY while CLIP is loading, null if it can't be resolved (CLIP missing and not a
     * table line). Call from a background thread.
     */
    static float[] resolveLook(String text) throws Exception {
        String t = text.strip().toLowerCase(Locale.ROOT);
        if (texts.isEmpty()) loadTable();
        int exact = texts.indexOf(t);
        if (exact >= 0) return settings.get(exact).clone();
        String key = "look|" + tableHash + "|" + t;
        Path cacheFile = FabricLoader.getInstance().getConfigDir().resolve("restyler").resolve("vibes_cache.bin");
        synchronized (CACHE_LOCK) {
            if (lookCache == null) {
                lookCache = new HashMap<>();
                for (Map.Entry<String, float[]> e : readCache(cacheFile).entrySet())
                    if (e.getKey().startsWith("look|")) lookCache.put(e.getKey(), e.getValue());
            }
            float[] c = lookCache.get(key);
            if (c != null && c.length == NM + NS + NE) return c.clone();
        }
        if (clip == null) {
            if (clipMissing) return null;
            if (!loading) ensureLoaded(() -> {});
            return clipMissing ? null : RETRY;
        }
        float[] v = compute(t, new String[1]);
        synchronized (CACHE_LOCK) {
            lookCache.put(key, v.clone());
            Map<String, float[]> all = readCache(cacheFile);
            all.put(key, v.clone());
            writeCache(cacheFile, all);
        }
        return v;
    }

    private static String summary(String[] names, float[] v, int off, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (v[off + i] >= 0.05f) b.append(b.length() > 0 ? ", " : "").append(names[i]).append(' ')
                    .append(Math.round(v[off + i] * 100)).append('%');
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    private static float[] run(OrtEnvironment env, OrtSession s, ClipTokenizer k, String text) throws Exception {
        long[][][] e = k.encode(text);
        try (OnnxTensor ids = OnnxTensor.createTensor(env, e[0]);
             OnnxTensor mask = OnnxTensor.createTensor(env, e[1]);
             OrtSession.Result r = s.run(java.util.Map.of("input_ids", ids, "attention_mask", mask))) {
            return ((float[][]) r.get(0).getValue())[0].clone();
        }
    }

    private static float[] centred(float[] v, float[] m) {
        float[] o = new float[v.length];
        for (int i = 0; i < o.length; i++) o[i] = v[i] - m[i];
        return normalize(o);
    }

    private static float dot(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static float[] normalize(float[] v) {
        float n = (float) Math.sqrt(dot(v, v));
        if (n > 1e-8f) for (int i = 0; i < v.length; i++) v[i] /= n;
        return v;
    }
}
