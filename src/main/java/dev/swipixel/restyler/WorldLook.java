package dev.swipixel.restyler;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * World looks: biome / time / weather / zone driven looks (layers 1-5, see LookResolver) and the player's pick
 * (layer 6: pinned = the sliders exactly as before).
 *
 *   every client tick: sky access eases (3 s); every 10 ticks: resolve the target look on the CPU (25 biome samples,
 *   no GPU work); every tick: the shown look eases toward it (fade seconds, wet 30 s up / 120 s down) and is handed
 *   to Mood / Style / Effect through their auto path (never saved to the *.properties files).
 *
 * Files in config/restyler: world.properties, biomes.txt (optional override of the built-in one), zones/<salt>.json.
 */
public final class WorldLook {

    public static final WorldLook INSTANCE = new WorldLook();

    private static final float[] EMPTY = new float[0];
    private static final String[] FADE_KEYS = {"fade_global", "fade_biomes", "fade_time_weather", "fade_variation", "fade_zones"};

    // ---- settings (world.properties)
    private boolean enabled = true, biomes = true, timeWeather = true, zonesOn = true, pinned = false;
    private float variation = 1f, wavelength = 450f, fadeSeconds = 4f, wetRise = 30f, wetDry = 120f;
    private String global = "";
    private final String[] fade = {"time", "time", "time", "time", "time"};
    private LookSpec globalSpec;

    // ---- data
    private BiomeTable table = BiomeTable.empty();
    private List<Zones.Zone> zones = new ArrayList<>();
    private String salt = null;
    private boolean inWorld = false;

    // ---- prompts (resolved on one background thread, never on the render thread)
    private final Map<String, float[]> prompts = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "restyler-worldlook");
        t.setDaemon(true);
        return t;
    });

    // ---- state
    private final float[] target = new float[Slots.N], eased = new float[Slots.N];
    private final float[] mBuf = new float[Slots.NM], sBuf = new float[Slots.NS], eBuf = new float[Slots.NE];
    private boolean haveTarget = false, snapNext = true, autoApplied = false, skyInit = false, crossfadeSaid = false;
    private volatile boolean dirty = true;
    private float sky = 1f;
    private int ticks = 0;
    private long lastNs = 0;
    private LookResolver.Result last;
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> warned = new HashSet<>();

    private WorldLook() {}

    // ------------------------------------------------------------ files

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("restyler");
    }

    public void init() {
        loadSettings();
        loadTable();
    }

    private void loadSettings() {
        Path f = dir().resolve("world.properties");
        Properties p = new Properties();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (Exception e) {
                warn("world.properties unreadable (" + e.getMessage() + "), using defaults");
            }
        }
        enabled = bool(p, "enabled", true);
        biomes = bool(p, "biomes", true);
        timeWeather = bool(p, "time_weather", true);
        zonesOn = bool(p, "zones", true);
        pinned = bool(p, "pinned", false);
        variation = num(p, "variation", 1f, 0f, 5f);
        wavelength = num(p, "variation_wavelength", 450f, 16f, 100000f);
        fadeSeconds = num(p, "fade_seconds", 4f, 0f, 120f);
        wetRise = num(p, "wet_rise_seconds", 30f, 0f, 3600f);
        wetDry = num(p, "wet_dry_seconds", 120f, 0f, 3600f);
        global = p.getProperty("global", "").strip();
        for (int i = 0; i < fade.length; i++) fade[i] = fadeMode(p.getProperty(FADE_KEYS[i], "time"), "world.properties " + FADE_KEYS[i]);
        compileGlobal();
        saveSettings();          // creates the file with defaults / fills in missing keys
    }

    private void saveSettings() {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("biomes", Boolean.toString(biomes));
        p.setProperty("time_weather", Boolean.toString(timeWeather));
        p.setProperty("zones", Boolean.toString(zonesOn));
        p.setProperty("pinned", Boolean.toString(pinned));
        p.setProperty("variation", Float.toString(variation));
        p.setProperty("variation_wavelength", Float.toString(wavelength));
        p.setProperty("fade_seconds", Float.toString(fadeSeconds));
        p.setProperty("wet_rise_seconds", Float.toString(wetRise));
        p.setProperty("wet_dry_seconds", Float.toString(wetDry));
        p.setProperty("global", global);
        for (int i = 0; i < fade.length; i++) p.setProperty(FADE_KEYS[i], fade[i]);
        try {
            Files.createDirectories(dir());
            try (Writer w = Files.newBufferedWriter(dir().resolve("world.properties"), StandardCharsets.UTF_8)) {
                p.store(w, "Restyler world looks. pinned = the player's sliders win (/vibe auto unpins). variation 1.0 = 100 %."
                        + " fade_* = time | hard (crossfade: phase 2, acts like time). global = a slider line or \"prompt\".");
            }
        } catch (Exception e) {
            Restyler.LOG.warn("Restyler: could not save world.properties", e);
        }
    }

    private static boolean bool(Properties p, String k, boolean def) {
        return Boolean.parseBoolean(p.getProperty(k, Boolean.toString(def)).strip());
    }

    private float num(Properties p, String k, float def, float lo, float hi) {
        try {
            float v = Float.parseFloat(p.getProperty(k, Float.toString(def)).strip());
            if (!Float.isFinite(v)) throw new NumberFormatException();
            return Math.max(lo, Math.min(hi, v));
        } catch (NumberFormatException e) {
            warn("world.properties: bad number for " + k + ", using " + def);
            return def;
        }
    }

    private String fadeMode(String v, String where) {
        String m = v.strip().toLowerCase(Locale.ROOT);
        if (m.equals("time") || m.equals("hard") || m.equals("crossfade")) return m;
        warn(where + ": unknown fade '" + v + "' (time | hard | crossfade), using time");
        return "time";
    }

    private void compileGlobal() {
        globalSpec = null;
        if (global.isEmpty()) return;
        try {
            globalSpec = LookSpec.parse(global, LookSpec.Mode.BASE);
        } catch (IllegalArgumentException e) {
            warn("world.properties global: " + e.getMessage() + " - global look ignored");
        }
    }

    private static List<String> builtinLines() {
        try (InputStream in = WorldLook.class.getResourceAsStream("/assets/restyler/biomes.txt")) {
            if (in == null) return new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return new ArrayList<>(r.lines().toList());
            }
        } catch (Exception e) {
            Restyler.LOG.warn("Restyler: built-in biomes.txt unreadable", e);
            return new ArrayList<>();
        }
    }

    private static Path ownBiomes() {
        return dir().resolve("biomes.txt");
    }

    private void loadTable() {
        Path own = ownBiomes();
        List<String> lines;
        String name;
        if (Files.exists(own)) {
            try {
                lines = Files.readAllLines(own, StandardCharsets.UTF_8);
                name = "config/restyler/biomes.txt";
            } catch (Exception e) {
                warn("config/restyler/biomes.txt unreadable (" + e.getMessage() + "), using the built-in table");
                lines = builtinLines();
                name = "biomes.txt (built-in)";
            }
        } else {
            lines = builtinLines();
            name = "biomes.txt (built-in)";
        }
        table = BiomeTable.parse(lines, name);
        for (String w : table.warnings) warn(w);
        dirty = true;
    }

    private Path zoneFile() {
        if (salt == null) return null;
        return dir().resolve("zones").resolve(salt.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }

    private void saveZones() {
        try {
            Zones.save(zoneFile(), zones);
        } catch (Exception e) {
            Restyler.say("Could not save zones: " + e, false);
        }
    }

    /** /vibe world reload: every file again. */
    public void reload() {
        warned.clear();
        Vibe.retryClip();
        prompts.clear();
        loadSettings();
        loadTable();
        if (inWorld) {
            List<String> w = new ArrayList<>();
            zones = Zones.load(zoneFile(), w);
            for (String s : w) warn(s);
            validateBiomes(MinecraftClient.getInstance());
        }
        snapNext = true;
        dirty = true;
    }

    // ------------------------------------------------------------ warnings

    private void warn(String msg) {
        synchronized (warnings) {
            if (warned.add(msg)) warnings.add(msg);
        }
        Restyler.LOG.warn("Restyler world looks: {}", msg);
    }

    private void flushWarnings(MinecraftClient mc) {
        if (mc.player == null) return;
        List<String> out;
        synchronized (warnings) {
            if (warnings.isEmpty()) return;
            out = new ArrayList<>(warnings);
            warnings.clear();
        }
        int n = 0;
        for (String w : out) {
            if (n++ == 6) {
                Restyler.say("World looks: " + (out.size() - 6) + " more warnings in the log", false);
                break;
            }
            Restyler.say("World looks: " + w, false);
        }
    }

    // ------------------------------------------------------------ world join / leave

    private void join(MinecraftClient mc) {
        inWorld = true;
        salt = computeSalt(mc);
        List<String> w = new ArrayList<>();
        zones = Zones.load(zoneFile(), w);
        for (String s : w) warn(s);
        validateBiomes(mc);
        haveTarget = false;
        snapNext = true;          // the same spot looks the same right away, no fade-in after joining
        skyInit = false;
        ticks = 10;
        last = null;
    }

    public void leave() {
        if (!inWorld) return;
        inWorld = false;
        zones = new ArrayList<>();
        salt = null;
        haveTarget = false;
        last = null;
        releaseAuto();
    }

    private static String computeSalt(MinecraftClient mc) {
        try {
            if (mc.isIntegratedServerRunning() && mc.getServer() != null) {
                Path root = mc.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
                if (root.getFileName() != null) return root.getFileName().toString();
            }
            ServerInfo s = mc.getCurrentServerEntry();
            if (s != null && s.address != null) return s.address.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            Restyler.LOG.warn("Restyler: could not name the world", e);
        }
        return "unknown";
    }

    private void validateBiomes(MinecraftClient mc) {
        if (mc.world == null) return;
        Registry<Biome> reg = mc.world.getRegistryManager().get(RegistryKeys.BIOME);
        for (BiomeTable.Entry e : table.entries.values()) {
            if (e.id.equals(BiomeTable.STAR)) continue;
            Identifier id = Identifier.tryParse(e.id.contains(":") ? e.id : "minecraft:" + e.id);
            if (id == null || !reg.containsId(id))
                warn(table.fileName + ":" + e.line + ": unknown biome '" + e.id + "' - its lines are skipped");
        }
    }

    // ------------------------------------------------------------ per tick

    public void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            leave();
            return;
        }
        if (!inWorld) join(mc);
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.05f : Math.min(0.5f, (now - lastNs) / 1e9f);
        lastNs = now;
        flushWarnings(mc);

        // sky access: no sky, no time / weather (eased over ~3 s: time constant 1 s)
        float skyNow = mc.world.getLightLevel(LightType.SKY, BlockPos.ofFloored(mc.player.getEyePos())) / 15f;
        sky = skyInit ? LookMath.ease(sky, skyNow, dt, 1f) : skyNow;
        skyInit = true;

        if (!enabled) {
            releaseAuto();
            return;
        }
        if (++ticks >= 10 || dirty) {
            ticks = 0;
            dirty = false;
            resolve(mc, false);
        }
        if (!haveTarget || pinned) {
            releaseAuto();
            return;
        }
        if (!autoApplied) {                    // world looks take over: start from what is shown now
            for (int i = 0; i < Slots.NM; i++) eased[i] = Mood.manual(i);
            for (int i = 0; i < Slots.NS; i++) eased[Slots.NM + i] = Style.manual(i);
            for (int i = 0; i < Slots.NE; i++) eased[Slots.NM + Slots.NS + i] = Effect.manual(i);
        }
        if (snapNext) {
            System.arraycopy(target, 0, eased, 0, Slots.N);
            snapNext = false;
        } else {
            LookMath.easeLook(eased, target, dt, fadeSeconds, wetRise, wetDry, Slots.WET);
        }
        push();
    }

    private void push() {
        System.arraycopy(eased, 0, mBuf, 0, Slots.NM);
        System.arraycopy(eased, Slots.NM, sBuf, 0, Slots.NS);
        System.arraycopy(eased, Slots.NM + Slots.NS, eBuf, 0, Slots.NE);
        Mood.setAuto(mBuf);
        Style.setAuto(sBuf);
        Effect.setAuto(eBuf);
        autoApplied = true;
    }

    private void releaseAuto() {
        if (!autoApplied) return;
        Mood.setAuto(null);
        Style.setAuto(null);
        Effect.setAuto(null);
        autoApplied = false;
    }

    private LookResolver.Result resolve(MinecraftClient mc, boolean why) {
        ClientWorld w = mc.world;
        Vec3d pos = mc.player.getPos();
        BlockPos here = BlockPos.ofFloored(pos);
        LookResolver.Env e = new LookResolver.Env();
        e.x = pos.x;
        e.y = pos.y;
        e.z = pos.z;
        e.time = (float) (w.getTimeOfDay() % 24000L);
        e.rain = w.getRainGradient(1f);
        e.thunder = w.getThunderGradient(1f);
        e.sky = sky;
        e.conditions = w.getDimension().hasSkyLight() && !w.getDimension().hasFixedTime();
        e.salt = salt == null ? "" : salt;
        e.here = precip(w.getBiome(here), here);

        // 5 x 5 world-aligned grid, 16 blocks apart (radius 32), weighted exp(-(d/24)^2) by distance to the player
        Map<String, LookResolver.Sample> byId = new LinkedHashMap<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (double[] g : LookMath.grid(pos.x, pos.z)) {
            float wt = (float) g[2];
            RegistryEntry<Biome> b = w.getBiome(m.set((int) g[0], here.getY(), (int) g[1]));
            String id = b.getKey().map(k -> BiomeTable.normId(k.getValue().toString())).orElse("unknown");
            LookResolver.Sample s = byId.get(id);
            if (s == null) byId.put(id, new LookResolver.Sample(id, wt, precip(b, here)));
            else s.weight += wt;
        }
        LookResolver.Settings s = new LookResolver.Settings();
        s.biomes = biomes;
        s.timeWeather = timeWeather;
        s.zones = zonesOn;
        s.variation = variation;
        s.wavelength = wavelength;
        s.global = globalSpec;
        LookResolver.Result r = LookResolver.resolve(s, table, new ArrayList<>(byId.values()), e, zones, this::prompt, why);

        // "hard" layers / zones: when what they contribute changes, the shown look jumps instead of fading
        if (last != null && !snapNext) {
            for (int l = 0; l < LookResolver.L_ZONES; l++) {
                if (mode(fade[l]).equals("hard") && differs(r.delta[l], last.delta[l])) snapNext = true;
            }
            for (Zones.Zone z : zones) {
                String f = mode(z.fade != null ? z.fade : fade[LookResolver.L_ZONES]);
                if (f.equals("hard") && differs(r.zoneDelta.get(z.name), last.zoneDelta.get(z.name))) snapNext = true;
            }
        }
        System.arraycopy(r.look, 0, target, 0, Slots.N);
        haveTarget = true;
        last = r;
        return r;
    }

    /** crossfade is phase 2 (style image mixing, not built yet): it behaves like time. */
    private String mode(String f) {
        if (f.equals("crossfade")) {
            if (!crossfadeSaid) warn("fade 'crossfade' is phase 2 and not built yet - it acts like 'time' for now");
            crossfadeSaid = true;
            return "time";
        }
        return f;
    }

    private static boolean differs(float[] a, float[] b) {
        if (a == null || b == null) return a != b && !allZero(a == null ? b : a);
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 2e-3f) return true;
        return false;
    }

    private static boolean allZero(float[] v) {
        for (float x : v) if (Math.abs(x) > 2e-3f) return false;
        return true;
    }

    private static LookResolver.Precip precip(RegistryEntry<Biome> b, BlockPos pos) {
        return switch (b.value().getPrecipitation(pos)) {
            case NONE -> LookResolver.Precip.NONE;
            case SNOW -> LookResolver.Precip.SNOW;
            default -> LookResolver.Precip.RAIN;
        };
    }

    /** Resolved prompt, or null while pending / unresolvable. Resolution runs on the background thread, once. */
    private float[] prompt(String text) {
        float[] v = prompts.get(text);
        if (v != null) return v == EMPTY ? null : v;
        if (pending.add(text)) {
            exec.submit(() -> {
                try {
                    float[] r = Vibe.resolveLook(text);
                    if (r == null) {
                        prompts.put(text, EMPTY);
                        warn("prompt \"" + text + "\" can't be resolved (not a vibe table line, and CLIP isn't available: "
                                + Vibe.status() + ") - that layer is empty");
                    } else if (r != Vibe.RETRY) {
                        prompts.put(text, r);
                        dirty = true;               // fades in on the next tick
                    }
                } catch (Throwable ex) {
                    prompts.put(text, EMPTY);
                    warn("prompt \"" + text + "\" failed: " + ex);
                } finally {
                    pending.remove(text);
                }
            });
        }
        return null;
    }

    // ------------------------------------------------------------ pinning (layer 6)

    /** Mood / Style / Effect call this before any manual change: copy the shown world look into the sliders and pin. */
    void beforeManualChange() {
        if (pinned) return;
        if (autoApplied) {
            float[] v = eased.clone();
            releaseAuto();
            Mood.setManual(v, 0);
            Style.setManual(v, Slots.NM);
            Effect.setManual(v, Slots.NM + Slots.NS);
        }
        pinned = true;
        saveSettings();
    }

    public boolean pinned() {
        return pinned;
    }

    public void setPinned(boolean p) {
        if (p) {
            beforeManualChange();
        } else {
            pinned = false;
            saveSettings();
            dirty = true;
        }
    }

    public boolean active() {
        return enabled && !pinned && inWorld && haveTarget;
    }

    // ------------------------------------------------------------ menu

    public boolean biomes() { return biomes; }
    public boolean timeWeather() { return timeWeather; }
    public boolean zonesOn() { return zonesOn; }
    public float variation() { return variation; }
    public float fadeSeconds() { return fadeSeconds; }
    public void setBiomes(boolean v) { biomes = v; changed(); }
    public void setTimeWeather(boolean v) { timeWeather = v; changed(); }
    public void setZonesOn(boolean v) { zonesOn = v; changed(); }
    public void setVariation(float v) { variation = Math.max(0f, Math.min(5f, v)); changed(); }
    public void setFadeSeconds(float v) { fadeSeconds = Math.max(0f, Math.min(120f, v)); changed(); }

    private void changed() {
        saveSettings();
        dirty = true;
    }

    public String statusTag() {
        if (!enabled) return "world looks disabled (world.properties)";
        return pinned ? "world looks: pinned (your sliders)" : "world looks: auto";
    }

    public String biomeStatus() {
        if (last == null || last.biomes.isEmpty()) return inWorld ? "biomes: -" : "biomes: (not in a world)";
        StringBuilder b = new StringBuilder("biomes: ");
        int n = 0;
        for (Map.Entry<String, Float> e : last.biomes.entrySet().stream()
                .sorted((x, y) -> Float.compare(y.getValue(), x.getValue())).toList()) {
            if (n++ == 4) { b.append(", ..."); break; }
            b.append(n > 1 ? ", " : "").append(e.getKey()).append(' ').append(Math.round(e.getValue() * 100)).append('%');
        }
        return b.toString();
    }

    public String lookSummary() {
        return haveTarget ? "look: " + Slots.summary(target) : "look: -";
    }

    // ------------------------------------------------------------ commands

    private static void say(String s) {
        Restyler.say(s, false);
    }

    /** A command value: slider line kept as written, anything else becomes a "prompt". Throws on a broken slider line. */
    static String toValue(String text, LookSpec.Mode mode) {
        String t = text.strip();
        if (t.startsWith("\"")) {
            LookSpec.parse(t, mode);
            return t;
        }
        String first = t.split("[\\s,]+")[0];
        boolean slidery = Slots.index(first) >= 0 && t.matches("(?s).*\\s[+-]?[0-9].*");
        if (slidery) {
            LookSpec.parse(t, mode);         // throws with the reason
            return t;
        }
        String p = t.replace("\"", "'");
        LookSpec.parse("\"" + p + "\"", mode);
        return "\"" + p + "\"";
    }

    public void auto() {
        setPinned(false);
        say("World looks: ON - biomes, time, weather and zones drive the look (any slider or /vibe <text> pins again)");
    }

    public void globalCommand(String text) {
        if (text == null || text.isBlank()) {
            say("Global look: " + (global.isEmpty() ? "off" : global) + "   (/vibe global <text> | off)");
            return;
        }
        if (text.strip().equalsIgnoreCase("off")) {
            global = "";
        } else {
            try {
                global = toValue(text, LookSpec.Mode.BASE);
            } catch (IllegalArgumentException e) {
                say("Global look not set: " + e.getMessage());
                return;
            }
        }
        compileGlobal();
        changed();
        say("Global look: " + (global.isEmpty() ? "off" : global) + (pinned ? "  (pinned: /vibe auto to see it)" : ""));
    }

    /** /vibe biome here|<biome> <text> | @<cond> <line> | ~ <ranges> | reset, /vibe biome list. */
    public void biomeCommand(String args) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String a = args == null ? "" : args.strip();
        if (a.isEmpty()) {
            say("/vibe biome here|<biome> <text>  |  @<dawn|noon|dusk|night|rain|snow|thunder> <line>  |  ~ <ranges>  |  reset;"
                    + "  /vibe biome list");
            return;
        }
        if (a.equalsIgnoreCase("list")) {
            if (!Files.exists(ownBiomes())) {
                say("No custom biome lines (config/restyler/biomes.txt doesn't exist; the built-in table is used).");
                return;
            }
            try {
                Set<String> c = BiomeTable.customBiomes(Files.readAllLines(ownBiomes(), StandardCharsets.UTF_8), builtinLines());
                say(c.isEmpty() ? "No custom biome lines." : "Custom biome lines: " + String.join(", ", c));
            } catch (Exception e) {
                say("Could not read config/restyler/biomes.txt: " + e);
            }
            return;
        }
        String[] p = a.split("\\s+", 2);
        String id;
        if (p[0].equalsIgnoreCase("here")) {
            if (mc.world == null || mc.player == null) { say("Not in a world."); return; }
            id = mc.world.getBiome(mc.player.getBlockPos()).getKey()
                    .map(k -> BiomeTable.normId(k.getValue().toString())).orElse(null);
            if (id == null) { say("Can't tell the biome here."); return; }
        } else {
            id = BiomeTable.normId(p[0]);
            if (!id.equals(BiomeTable.STAR) && mc.world != null) {
                Identifier ident = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                if (ident == null || !mc.world.getRegistryManager().get(RegistryKeys.BIOME).containsId(ident)) {
                    say("Unknown biome '" + p[0] + "'. Tab completes biome ids; 'here' is the biome you stand in.");
                    return;
                }
            }
        }
        String rest = p.length > 1 ? p[1].strip() : "";
        if (rest.isEmpty()) {
            BiomeTable.Entry e = table.get(id);
            BiomeTable.Entry st = table.star();
            String nameBase = "\"" + id.substring(id.indexOf(':') + 1).replace('_', ' ') + "\"   (from its name)";
            if (e == null) {
                say(id + " = " + (st != null && st.base != null ? st.base.source + "   (from *)" : nameBase));
                if (st != null) for (Map.Entry<BiomeTable.Cond, LookSpec> c : st.conds.entrySet())
                    say(id + " @" + c.getKey().key + " = " + c.getValue().source + "   (from *)");
                return;
            }
            say(id + " = " + (e.base != null ? e.base.source : st != null && st.base != null ? st.base.source + "   (from *)" : nameBase));
            for (Map.Entry<BiomeTable.Cond, LookSpec> c : e.conds.entrySet()) say(id + " @" + c.getKey().key + " = " + c.getValue().source);
            if (e.var != null) say(id + " ~ = " + e.var.source);
            return;
        }
        List<String> cfg;
        try {
            cfg = Files.exists(ownBiomes()) ? new ArrayList<>(Files.readAllLines(ownBiomes(), StandardCharsets.UTF_8)) : builtinLines();
        } catch (Exception e) {
            say("Could not read config/restyler/biomes.txt: " + e);
            return;
        }
        String done;
        try {
            if (rest.equalsIgnoreCase("reset")) {
                cfg = BiomeTable.resetBiome(cfg, builtinLines(), id);
                done = id + " is back to the built-in lines";
            } else if (rest.startsWith("@")) {
                String[] q = rest.split("\\s+", 2);
                BiomeTable.Cond c = BiomeTable.Cond.of(q[0].substring(1).toLowerCase(Locale.ROOT));
                if (c == null) { say("Unknown condition '" + q[0] + "' (dawn noon dusk night rain snow thunder)"); return; }
                if (q.length < 2) { say("Missing the line after " + q[0]); return; }
                String v = toValue(q[1], LookSpec.Mode.COND);
                cfg = BiomeTable.setLine(cfg, new BiomeTable.Key(id, c, false), v);
                done = id + " @" + c.key + " = " + v;
            } else if (rest.startsWith("~")) {
                String v = rest.substring(1).strip();
                LookSpec.parse(v, LookSpec.Mode.VARIATION);
                cfg = BiomeTable.setLine(cfg, new BiomeTable.Key(id, null, true), v);
                done = id + " ~ = " + v;
            } else {
                String v = toValue(rest, LookSpec.Mode.BASE);
                cfg = BiomeTable.setLine(cfg, new BiomeTable.Key(id, null, false), v);
                done = id + " = " + v;
            }
        } catch (IllegalArgumentException e) {
            say("Not changed: " + e.getMessage());
            return;
        }
        try {
            Files.createDirectories(dir());
            Files.write(ownBiomes(), cfg, StandardCharsets.UTF_8);
        } catch (Exception e) {
            say("Could not write config/restyler/biomes.txt: " + e);
            return;
        }
        loadTable();
        say("Biome look: " + done + "  (config/restyler/biomes.txt)" + (pinned ? "  - pinned: /vibe auto to see it" : ""));
    }

    /** Returns false if the text isn't a zone command (then it is an ordinary /vibe prompt). */
    public boolean zoneCommand(String args) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String a = args == null ? "" : args.strip();
        String[] t = a.isEmpty() ? new String[0] : a.split("\\s+");
        String sub = t.length == 0 ? "" : t[0].toLowerCase(Locale.ROOT);
        if (!Set.of("", "add", "box", "remove", "list", "edit", "fade").contains(sub)) return false;
        if (!inWorld || mc.player == null) { say("Zones need a world."); return true; }
        try {
            switch (sub) {
                case "" -> say("/vibe zone add <name> <radius> [blend] [priority] <text> | box <name> <x1 y1 z1 x2 y2 z2>"
                        + " [blend] [priority] <text> | remove <name> | list | edit <name> <text> | fade <name> time|hard|crossfade");
                case "list" -> {
                    if (zones.isEmpty()) { say("No zones in this world (" + salt + ")."); break; }
                    Vec3d pp = mc.player.getPos();
                    for (Zones.Zone z : zones)
                        say(String.format(Locale.ROOT, "%s: %s, blend %.0f, prio %d, %s%s, fade %s, weight here %.2f",
                                z.name, z.shapeText(), z.blend, z.priority, z.offset ? "offset " : "", z.look,
                                z.fade == null ? fade[LookResolver.L_ZONES] : z.fade, z.weight(pp.x, pp.y, pp.z)));
                }
                case "remove" -> {
                    Zones.Zone z = need(t, 1);
                    zones.remove(z);
                    saveZones();
                    say("Zone " + z.name + " removed");
                }
                case "edit" -> {
                    Zones.Zone z = need(t, 1);
                    String text = tail(a, 2);
                    if (text.isEmpty()) { say("Missing the look after the name"); break; }
                    Zones.Zone nz = copyLook(z, text);
                    zones.set(zones.indexOf(z), nz);
                    Zones.sort(zones);
                    saveZones();
                    say("Zone " + z.name + ": " + (nz.offset ? "offset " : "") + nz.look);
                }
                case "fade" -> {
                    Zones.Zone z = need(t, 1);
                    if (t.length < 3) { say("fade <name> time|hard|crossfade"); break; }
                    String f = t[2].toLowerCase(Locale.ROOT);
                    if (!Set.of("time", "hard", "crossfade").contains(f)) { say("time | hard | crossfade"); break; }
                    z.fade = f;
                    saveZones();
                    say("Zone " + z.name + " fade: " + f + (f.equals("crossfade") ? " (phase 2, acts like time for now)" : ""));
                }
                case "add", "box" -> {
                    boolean box = sub.equals("box");
                    if (t.length < 2) { say("Missing the zone name"); break; }
                    Zones.Zone z = new Zones.Zone();
                    z.name = t[1];
                    int i = 2;
                    Vec3d pp = mc.player.getPos();
                    if (box) {
                        if (t.length < 8) { say("box <name> <x1> <y1> <z1> <x2> <y2> <z2> [blend] [priority] <text>"); break; }
                        double[] c = new double[6];
                        for (int k = 0; k < 6; k++) c[k] = coord(t[2 + k], k % 3 == 0 ? pp.x : k % 3 == 1 ? pp.y : pp.z);
                        z.box = true;
                        z.x1 = Math.min(c[0], c[3]); z.x2 = Math.max(c[0], c[3]) + 1;     // block corners, inclusive
                        z.y1 = Math.min(c[1], c[4]); z.y2 = Math.max(c[1], c[4]) + 1;
                        z.z1 = Math.min(c[2], c[5]); z.z2 = Math.max(c[2], c[5]) + 1;
                        i = 8;
                    } else {
                        if (t.length < 3) { say("add <name> <radius> [blend] [priority] <text>"); break; }
                        z.radius = Double.parseDouble(t[2]);
                        z.cx = Math.round(pp.x * 10) / 10.0;
                        z.cy = Math.round(pp.y * 10) / 10.0;
                        z.cz = Math.round(pp.z * 10) / 10.0;
                        i = 3;
                    }
                    if (i + 1 < t.length && isNum(t[i])) z.blend = Math.max(0, Double.parseDouble(t[i++]));
                    if (i + 1 < t.length && t[i].matches("-?\\d+")) z.priority = Integer.parseInt(t[i++]);
                    String text = tail(a, i);
                    if (text.isEmpty()) { say("Missing the zone's look"); break; }
                    Zones.Zone nz = copyLook(z, text);
                    Zones.Zone old = find(z.name);
                    if (old != null) zones.remove(old);
                    zones.add(nz);
                    Zones.sort(zones);
                    saveZones();
                    say("Zone " + nz.name + (old != null ? " replaced: " : ": ") + nz.shapeText() + ", blend "
                            + Math.round(nz.blend) + ", prio " + nz.priority + ", " + (nz.offset ? "offset " : "") + nz.look
                            + (pinned ? "  - pinned: /vibe auto to see it" : ""));
                }
                default -> { }
            }
        } catch (IllegalArgumentException e) {
            say("Zone not changed: " + e.getMessage());
        }
        dirty = true;
        return true;
    }

    private Zones.Zone need(String[] t, int i) {
        if (t.length <= i) throw new IllegalArgumentException("missing the zone name");
        Zones.Zone z = find(t[i]);
        if (z == null) throw new IllegalArgumentException("no zone '" + t[i] + "' (/vibe zone list)");
        return z;
    }

    private Zones.Zone find(String name) {
        for (Zones.Zone z : zones) if (z.name.equalsIgnoreCase(name)) return z;
        return null;
    }

    /** A copy of z with a new look: only-offset slider lines (+N / -N) make an offset zone, anything else a set zone. */
    private static Zones.Zone copyLook(Zones.Zone z, String text) {
        boolean off = LookSpec.onlyOffsets(text);
        String v = toValue(text, off ? LookSpec.Mode.OFFSET : LookSpec.Mode.BASE);
        Zones.Zone n = new Zones.Zone();
        n.name = z.name; n.box = z.box;
        n.cx = z.cx; n.cy = z.cy; n.cz = z.cz; n.radius = z.radius;
        n.x1 = z.x1; n.y1 = z.y1; n.z1 = z.z1; n.x2 = z.x2; n.y2 = z.y2; n.z2 = z.z2;
        n.blend = z.blend; n.priority = z.priority; n.fade = z.fade; n.lines = z.lines;
        n.look = v;
        n.offset = off;
        String err = n.compile();
        if (err != null) throw new IllegalArgumentException(err);
        return n;
    }

    private static boolean isNum(String s) {
        try { Double.parseDouble(s); return true; } catch (NumberFormatException e) { return false; }
    }

    /** "12", "~", "~-3" (relative to the player). */
    private static double coord(String s, double here) {
        try {
            if (s.startsWith("~")) return Math.floor(here) + (s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0);
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad coordinate '" + s + "'");
        }
    }

    /** The text after the first n whitespace-separated tokens. */
    private static String tail(String a, int n) {
        String s = a.strip();
        for (int i = 0; i < n && !s.isEmpty(); i++) {
            int sp = s.indexOf(' ');
            s = sp < 0 ? "" : s.substring(sp + 1).strip();
        }
        return s;
    }

    /** /vibe why: every layer's contribution here. */
    public void why() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) { say("Not in a world."); return; }
        if (!inWorld) join(mc);
        LookResolver.Result r = resolve(mc, true);
        say("World looks (" + (!enabled ? "disabled" : pinned ? "PINNED: your sliders are shown, /vibe auto" : "auto")
                + ", world '" + salt + "', time " + (mc.world.getTimeOfDay() % 24000L) + "):");
        for (String l : r.why) say(l);
        if (active()) say("  shown now (fading): " + Slots.summary(eased));
    }

    // ------------------------------------------------------------ tab completion

    public List<String> biomeIds() {
        MinecraftClient mc = MinecraftClient.getInstance();
        TreeSet<String> out = new TreeSet<>();
        if (mc.world != null) {
            for (Identifier id : mc.world.getRegistryManager().get(RegistryKeys.BIOME).getIds())
                out.add(BiomeTable.normId(id.toString()));
        }
        return new ArrayList<>(out);
    }

    public List<String> zoneNames() {
        List<String> out = new ArrayList<>();
        for (Zones.Zone z : zones) out.add(z.name);
        return out;
    }

    static List<String> sliderNames() {
        return Arrays.asList(Slots.KEYS);
    }
}
