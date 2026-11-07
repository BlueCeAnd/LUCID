package dev.swipixel.restyler;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Authenticraft / Restyler: shader looks on top of the game, all on the GPU, no network.
 *
 *   world drawn  -> onWorldEnd: camera + depth -> distances (Stabilizer)
 *   hand drawn   -> afterHand: hand mask
 *   before HUD   -> effects (wet, snow, overgrown, realistic light) -> mood -> art style -> lens (DOF / tilt-shift)
 *
 * Settings: config/restyler/restyler.properties (only "enabled"; moods / styles / effects keep their own files).
 */
public final class Restyler {

    public static final Restyler INSTANCE = new Restyler();
    public static final Logger LOG = LoggerFactory.getLogger("restyler");

    private boolean enabled = true;
    private Path configFile;

    private final Stabilizer stab = new Stabilizer();

    private final float[] sunDir = new float[3];
    private final float[] rayCol = new float[3];
    private final float[] envSun = {0f, 1f, 0f};
    private final float[] envSky = new float[3];
    private float envNight = 0f, envRain = 0f;

    private Restyler() {
    }

    // ------------------------------------------------------------
    // settings
    // ------------------------------------------------------------

    public void init() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("restyler");
        configFile = dir.resolve("restyler.properties");
        try {
            Files.createDirectories(dir);
            if (Files.exists(configFile)) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(configFile)) {
                    p.load(r);
                }
                enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
            }
            save();
        } catch (Exception e) {
            LOG.warn("Restyler: could not read {}, using defaults", configFile, e);
        }
    }

    private void save() {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", Boolean.toString(enabled));
            try (Writer w = Files.newBufferedWriter(configFile)) {
                p.store(w, "Restyler. enabled = shader looks on/off (key K).");
            }
        } catch (Exception e) {
            LOG.warn("Restyler: could not save {}", configFile, e);
        }
    }

    // ------------------------------------------------------------
    // controls (keys, menu, /restyler)
    // ------------------------------------------------------------

    public void toggle() {
        setEnabled(!enabled);
        say("Restyler " + (enabled ? "ON" : "OFF"), true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            stab.reset();   // the old focus / volume are from another place / time
        }
        save();
    }

    public String statusLine() {
        if (!enabled) {
            return "off";
        }
        int moods = Mood.wanted() ? 1 : 0, styles = Style.wanted() ? 1 : 0, fx = Effect.wanted() ? 1 : 0;
        String world = WorldLook.INSTANCE.active() ? "  | world look" : "";
        if (moods + styles + fx == 0) {
            return "on - nothing picked yet (Moods / Styles / Effects or /vibe)" + world;
        }
        return "on" + (moods > 0 ? "  | mood" : "") + (styles > 0 ? "  | style" : "") + (fx > 0 ? "  | effects" : "") + world;
    }

    private static boolean wanted() {
        return Mood.wanted() || Style.wanted() || Effect.wanted();
    }

    // ------------------------------------------------------------
    // per frame
    // ------------------------------------------------------------

    /** WorldRenderEvents.END (world drawn, hand not yet): camera + depth -> distances. */
    public void onWorldEnd(Matrix4f proj, Matrix4f view, double x, double y, double z) {
        if (!enabled || !wanted()) {
            return;
        }
        try {
            stab.onWorldEnd(MinecraftClient.getInstance().getFramebuffer(), proj, view, x, y, z);
        } catch (Throwable t) {
            failed(t);
        }
    }

    /** End of GameRenderer.renderWorld (hand drawn, GUI not yet): hand mask. */
    public void afterHand() {
        if (!enabled || !wanted()) {
            return;
        }
        try {
            stab.handPass(MinecraftClient.getInstance().getFramebuffer());
        } catch (Throwable t) {
            failed(t);
        }
    }

    public void onBeforeHud(DrawContext context) {
        if (!enabled) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            return;
        }
        try {
            postPasses(mc.getFramebuffer(), false);
            stab.endFrame();
        } catch (Throwable t) {
            failed(t);
        }
    }

    private void failed(Throwable t) {
        LOG.error("Restyler: turned off after a shader error", t);
        enabled = false;
        say("Restyler turned off after an error, see the log: " + t, false);
    }

    /** Effects -> mood -> art style -> lens. Each one fades in / out on its own. */
    private void postPasses(Framebuffer main, boolean moodWithoutDepth) {
        boolean fxOn = Effect.active(), moodOn = Mood.active(), styleOn = Style.active();   // advance all fades
        if (!stab.ready(main)) {
            if (moodOn && moodWithoutDepth) moodPass(main);
            return;
        }
        if (fxOn || styleOn) env();
        boolean sky = moodHasSky();
        float[] e = Effect.amounts();
        if (fxOn && Effect.world()) stab.fx(main, e, envSun, envSky, envRain, envNight, sky);
        if (moodOn) moodPass(main);
        if (styleOn) stab.style(main, Style.amounts(), envSun, envNight, sky);
        if (fxOn && Effect.lens()) stab.lens(main, e[Effect.DOF], e[Effect.TILT]);   // the camera films the drawn world
    }

    /** Sun (moon at night) direction, night amount, rain and the game's sky colour for the effects and the styles. */
    private void env() {
        MinecraftClient mc = MinecraftClient.getInstance();
        envSun[0] = 0f; envSun[1] = 1f; envSun[2] = 0f;
        envNight = 0f;
        envRain = 0f;
        envSky[0] = 0.5f; envSky[1] = 0.6f; envSky[2] = 0.8f;
        if (mc.world == null || !moodHasSky()) return;
        float td = mc.getRenderTickCounter().getTickDelta(true);
        float a = mc.world.getSkyAngle(td) * 6.2831855f;
        float sx = -(float) Math.sin(a), sy = (float) Math.cos(a);
        boolean night = sy < -0.05f;
        envSun[0] = night ? -sx : sx;
        envSun[1] = night ? -sy : sy;
        envNight = Math.max(0f, Math.min(1f, (0.1f - sy) / 0.3f));
        envRain = mc.world.getRainGradient(td);
        net.minecraft.util.math.Vec3d c = mc.world.getSkyColor(mc.gameRenderer.getCamera().getPos(), td);
        envSky[0] = (float) c.x; envSky[1] = (float) c.y; envSky[2] = (float) c.z;
    }

    /** Mood grade + brightness + torch bloom + sun/moon rays (overworld). */
    private void moodPass(Framebuffer main) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean sky = moodHasSky();
        float raysAmt = 0f;
        if (sky && mc.world != null) {
            // Minecraft's sun: (-sin a, cos a, 0), a = sky angle (0 = noon). At night the moon is opposite.
            float a = mc.world.getSkyAngle(mc.getRenderTickCounter().getTickDelta(true)) * 6.2831855f;
            float sx = -(float) Math.sin(a), sy = (float) Math.cos(a);
            boolean night = sy < -0.05f;
            sunDir[0] = night ? -sx : sx;
            sunDir[1] = night ? -sy : sy;
            sunDir[2] = 0f;
            float rain = mc.world.getRainGradient(1f);
            if (night) { rayCol[0] = 0.25f; rayCol[1] = 0.32f; rayCol[2] = 0.45f; }
            else { rayCol[0] = 1.0f; rayCol[1] = 0.86f; rayCol[2] = 0.62f; }
            raysAmt = Mood.rays() * (1f - rain) * (night ? 0.5f : 1f);
        }
        float night = 0f;
        if (sky && mc.world != null && Mood.darkNights()) {
            float sy = (float) Math.cos(mc.world.getSkyAngle(mc.getRenderTickCounter().getTickDelta(true)) * 6.2831855f);
            night = Math.max(0f, Math.min(1f, (0.1f - sy) / 0.3f));     // 0 by day, 1 once the sun is well down
        }
        stab.mood(main, moodParams(), Mood.totalWeight(), sky, Mood.brightness(), Mood.bloom(), raysAmt, sunDir, rayCol,
                night, Mood.torchRays());
    }

    /** Mood sky and aurora only where there is a real sky (overworld); elsewhere the void gets the fog. */
    private static boolean moodHasSky() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world != null && mc.world.getRegistryKey() == net.minecraft.world.World.OVERWORLD;
    }

    private static float[] moodParams() {
        float[] p = Mood.params();
        if (!moodHasSky()) {
            p[Mood.SKY_MIX] = 0f;
            p[Mood.AURORA] = 0f;
        }
        return p;
    }

    // ------------------------------------------------------------

    static void say(String msg, boolean actionBar) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(msg), actionBar);
        }
        LOG.info(msg);
    }
}
