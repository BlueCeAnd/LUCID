package dev.swipixel.restyler;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * GPU side of the Restyler: every look is a full-screen pass that reads the game's depth buffer.
 *
 * Textures (all in GL convention, row 0 = bottom):
 *   world  RG32F  full res   R = distance camera->surface in blocks (1e6 = sky), G = raw depth.  Written at the END
 *                            of world rendering (before the hand clears the depth buffer).
 *   cur    R32F   full res   same distance, but -1 where the HAND/held item is (depth at HUD time < world depth).
 *   emit   RG8    32^3       block volume around the camera: light level + kind (solid / leaves / plant / air).
 *
 * Passes before the HUD: fx (wet, snow, overgrown, realistic light) -> mood (+ bloom, shafts) -> style -> lens.
 */
final class Stabilizer {

    /** A camera: P * V (V = rotation only, Minecraft renders camera-relative) and its world position. */
    static final class Cam {
        final Matrix4f pv = new Matrix4f();
        final Matrix4f invPv = new Matrix4f();
        double x, y, z;
        boolean valid;

        void set(Matrix4f proj, Matrix4f view, double x, double y, double z) {
            pv.set(proj).mul(view);
            pv.invert(invPv);
            this.x = x;
            this.y = y;
            this.z = z;
            valid = true;
        }
    }

    final Cam curCam = new Cam();     // this frame (from the END of world rendering)
    private boolean worldReady = false;   // world texture written this frame
    private boolean curReady = false;     // hand mask written this frame

    private int vao = -1;
    private int progWorld, progCur, progMood, progBright, progDown, progUp, progStyle, progFx, progLens, progFocus;
    private int fw = -1, fh = -1;
    private static final int MAX_LIGHTS = 4;
    private final java.util.ArrayList<float[]> lights = new java.util.ArrayList<>(); // world x,y,z and intensity
    // blocks around the camera, RG8 32^3 (r = block light / 15, g = kind / 5), swept 8 layers per frame
    private static final int EV = 32;
    private int emitTex = -1;
    private final ByteBuffer emitBuf = org.lwjgl.BufferUtils.createByteBuffer(EV * EV * EV * 2);
    private long frameNo = 0, volFrame = -1;
    private float lastBloomK = 0f;                    // glow strength the mood added this frame (for the comic style)
    private final int[] focusFbo = {-1, -1}, focusTex = {-1, -1};
    private int focusIdx = 0;
    private long lastLensNs = 0;
    private int emitOx, emitOy, emitOz, emitLayer = 0;   // sweep in progress
    private int shownOx, shownOy, shownOz;               // the uploaded volume
    private boolean emitValid = false;
    static final int BLOOM_LEVELS = 4;                                   // 1/2, 1/4, 1/8, 1/16 of the screen
    private final int[] bDownFbo = new int[BLOOM_LEVELS], bDownTex = new int[BLOOM_LEVELS];
    private final int[] bUpFbo = new int[BLOOM_LEVELS], bUpTex = new int[BLOOM_LEVELS];
    private final int[] bW = new int[BLOOM_LEVELS], bH = new int[BLOOM_LEVELS];
    { java.util.Arrays.fill(bDownFbo, -1); java.util.Arrays.fill(bDownTex, -1);
      java.util.Arrays.fill(bUpFbo, -1); java.util.Arrays.fill(bUpTex, -1); }
    private int worldFbo = -1, worldTex = -1, curFbo = -1, curTex = -1;
    private int vanFbo = -1, vanTex = -1;      // copy of the current frame (shader can't read the screen it draws on)

    // ------------------------------------------------------------ shaders

    private static final String VS = """
            #version 150
            out vec2 uv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);   // one triangle covering the viewport
                uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /** Shared by every pass: the view ray and the exact surface point behind a pixel. */
    private static final String COMMON = """
            #version 150
            in vec2 uv;
            out vec4 o;
            const float SKY = 1.0e6;
            vec3 rayDir(mat4 invPv, vec2 s) {
                vec4 f = invPv * vec4(s * 2.0 - 1.0, 1.0, 1.0);
                return normalize(f.xyz / f.w);
            }
            // the exact surface point (camera-relative) at distance d along pixel s. View bobbing shifts the EYE inside
            // the projection, so "direction * distance" from the camera origin is off by up to ~0.1 block while walking:
            // take the real ray (near -> far plane point) and solve |o + t u| = d.
            vec3 pointAt(mat4 invPv, vec2 s, float d) {
                vec4 a = invPv * vec4(s * 2.0 - 1.0, -1.0, 1.0);
                vec4 b = invPv * vec4(s * 2.0 - 1.0, 1.0, 1.0);
                vec3 o = a.xyz / a.w;
                vec3 u = normalize(b.xyz / b.w - o);
                float ou = dot(o, u);
                return o + u * (-ou + sqrt(max(ou * ou - dot(o, o) + d * d, 0.0)));
            }
            """;

    // END of world rendering: main depth -> distance + raw depth
    private static final String FS_WORLD = COMMON + """
            uniform sampler2D uDepth;
            uniform mat4 uInvPv;
            void main() {
                float z = texture(uDepth, uv).r;
                if (z >= 0.99999) { o = vec4(SKY, z, 0.0, 1.0); return; }
                vec4 p = uInvPv * vec4(uv * 2.0 - 1.0, z * 2.0 - 1.0, 1.0);
                o = vec4(length(p.xyz / p.w), z, 0.0, 1.0);
            }
            """;

    // HUD time: depth now holds only the hand -> mark hand pixels -1
    private static final String FS_CUR = COMMON + """
            uniform sampler2D uDepth;
            uniform sampler2D uWorld;
            void main() {
                vec2 w = texture(uWorld, uv).rg;
                float z = texture(uDepth, uv).r;
                o = vec4(z < w.g - 1.0e-6 ? -1.0 : w.r, 0.0, 0.0, 1.0);
            }
            """;

    // BLOOM source (half res): coloured emitters glow; only the sun/moon makes shafts, marched through the sky mask.
    private static final String FS_BRIGHT = COMMON + """
            uniform sampler2D uScene;
            uniform sampler2D uCur;
            uniform vec2 uSunUv;
            uniform float uSunVis, uRays;
            uniform vec3 uRayCol;
            uniform float uBright, uBloomE, uFogDist, uFogAmt;
            uniform mat4 uInvPv;
            uniform float uTorchRays;
            uniform vec4 uTorch[4];    // camera-relative position (xyz) and strength (w) of the nearest torches
            uniform sampler3D uEmitVol;   // light-emitting blocks around the camera (32^3, block light / 15)
            uniform vec3 uEmitOrigin;     // its min corner, camera-relative
            uniform float uEmitOn;
            uniform int uTorchCount;
            uniform vec4 uFar[64];        // nearest light blocks (up to 32 away): corner, camera-relative + light / 15
            uniform int uFarCount;
            const float PI = 3.14159265359;
            vec3 toLin(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
            void main() {
                vec3 src = texture(uScene, uv).rgb;
                float mx = max(src.r, max(src.g, src.b)), mn = min(src.r, min(src.g, src.b));
                float sat = (mx - mn) / (mx + 1e-4);
                bool sky = texture(uCur, uv).r >= SKY * 0.1;
                float amb = dot(toLin(textureLod(uScene, uv, 5.0).rgb), vec3(0.2126, 0.7152, 0.0722));
                float e = smoothstep(0.5, 0.85, mx) * smoothstep(0.35, 0.65, sat) * (sky ? 0.0 : 1.0);
                float d0 = texture(uCur, uv).r;
                if (d0 > 0.0 && !sky) {
                    // the real blocks decide: only a block that emits light glows (a torch-lit poppy does not).
                    // Step 0.03 behind the visible surface so the point lies inside the block it belongs to.
                    vec3 pb = pointAt(uInvPv, uv, d0 + 0.03);
                    ivec3 cb = ivec3(floor(pb - uEmitOrigin));
                    bool known = false;
                    if (uEmitOn > 0.5 && all(greaterThanEqual(cb, ivec3(0))) && all(lessThan(cb, ivec3(32)))) {
                        float lv = texelFetch(uEmitVol, cb, 0).r;            // near: the block volume
                        e = lv > 0.0 ? smoothstep(0.35, 0.8, mx) * (0.4 + 0.6 * lv) : 0.0;
                        known = true;
                    } else {
                        float lv = 0.0;                                        // farther: the list of light blocks
                        for (int k = 0; k < 64; k++) {
                            if (k >= uFarCount) break;
                            vec3 c = uFar[k].xyz;
                            if (all(greaterThanEqual(pb, c - 0.05)) && all(lessThan(pb, c + 1.05))) { lv = uFar[k].w; break; }
                        }
                        if (lv > 0.0) { e = smoothstep(0.35, 0.8, mx) * (0.4 + 0.6 * lv); known = true; }
                        else if (uFarCount > 0 && d0 < 40.0) { e = 0.0; known = true; }   // scanned area, no light there
                    }
                    // beyond every scan: only white-hot pixels (flame and lava cores), never saturated flowers
                    if (!known) e *= smoothstep(0.88, 0.97, mx) * (1.0 - smoothstep(0.3, 0.55, sat));
                }
                e *= clamp(1.4 - amb * 5.0, 0.2, 1.0);       // in daylight orange blocks don't glow, in caves lava does
                vec3 b = toLin(src) * e * uBloomE;
                if (uRays > 0.0 && uSunVis > 0.0) {
                    vec2 dl = (uSunUv - uv) / 32.0;
                    vec2 q = uv;
                    float acc = 0.0, w = 1.0, wsum = 0.0;
                    for (int i = 0; i < 32; i++) {
                        q += dl;
                        vec2 qc = clamp(q, 0.001, 0.999);
                        acc += (texture(uCur, qc).r >= SKY * 0.1 ? 1.0 : 0.0) * w;
                        wsum += w;
                        w *= 0.95;
                    }
                    float fall = exp(-length((uSunUv - uv) * vec2(1.6, 1.0)) * 2.6);   // strongest near the light
                    float lit = acc / wsum;
                    float d = texture(uCur, uv).r;
                    float fogAtten = d < 0.0 || d >= SKY * 0.1 ? 1.0
                            : exp(-max(d - 2.0, 0.0) / max(uFogDist, 1.0) * uFogAmt);
                    b += uRayCol * lit * lit * fall * uRays * uSunVis * fogAtten * 0.12;
                }
                float targetDepth = texture(uCur, uv).r;
                float torchScatter = 0.0;
                if (uTorchRays > 0.0 && uFogAmt > 0.0 && targetDepth > 0.0) {
                    // Light scattered by the AIR between the eye and this pixel (single scattering of a point light
                    // along the view ray, closed form) - so it does not depend on the torch being on screen: walking
                    // up to / past a torch you stay inside its glow, and far torches fade with the fog, not a cutoff.
                    float Lr = min(targetDepth, 96.0);                    // in front of the sky: 96 blocks of air
                    vec3 vdir = rayDir(uInvPv, uv);
                    float sigmaT = uFogAmt / max(uFogDist, 1.0);
                    for (int k = 0; k < 4; k++) {
                        if (k >= uTorchCount) break;
                        vec4 T = uTorch[k];
                        vec3 source = T.xyz;
                        float t0 = dot(source, vdir);                     // closest approach of the ray to the torch
                        float h = max(length(source - vdir * t0), 0.2);
                        float air = (atan((Lr - t0) / h) - atan(-t0 / h)) / h;
                        if (air < 1e-3) continue;
                        float Tpath = exp(-sigmaT * (max(t0, 0.0) + h));
                        vec3 x0 = vdir * clamp(t0, 0.0, Lr);
                        // shadow: solid blocks between that point and the torch (3D, from the block scan) -> shafts
                        float visibility = 1.0;
                        if (uEmitOn > 0.5) {
                            vec3 a = x0 - uEmitOrigin, b = source - uEmitOrigin;
                            for (int i = 1; i < 8; i++) {
                                ivec3 c = ivec3(floor(mix(a, b, float(i) / 8.0)));
                                if (all(greaterThanEqual(c, ivec3(0))) && all(lessThan(c, ivec3(32)))
                                        && abs(texelFetch(uEmitVol, c, 0).g * 5.0 - 3.0) < 0.5) { visibility = 0.0; break; }
                            }
                        }
                        float cosTheta = clamp(dot(normalize(x0 - source + vec3(1e-4)), -vdir), -1.0, 1.0);
                        float g = 0.35;
                        float phaseDenom = max(1.0 + g * g - 2.0 * g * cosTheta, 0.01);
                        float phase = (1.0 - g * g) / (4.0 * PI * pow(phaseDenom, 1.5));
                        float phaseWeight = min(4.0 * PI * phase, 3.0);
                        float energy = T.w * sigmaT * 0.85 * air * Tpath * phaseWeight * visibility * uTorchRays * 1.25;
                        torchScatter += energy * 0.28;
                    }
                }
                // RGB remains the ordinary bloom pass; alpha carries a separate fog-scattering layer.
                o = vec4(b, torchScatter);
            }
            """;

    // dual-filter blur: 4 taps down, tent up (+ the finer level)
    private static final String FS_DOWN = """
            #version 150
            in vec2 uv;
            out vec4 o;
            uniform sampler2D uSrc;
            uniform vec2 uHalf;     // half a source texel
            void main() {
                o = 0.25 * (texture(uSrc, uv + vec2(-uHalf.x, -uHalf.y)) + texture(uSrc, uv + vec2(uHalf.x, -uHalf.y))
                          + texture(uSrc, uv + vec2(-uHalf.x, uHalf.y)) + texture(uSrc, uv + vec2(uHalf.x, uHalf.y)));
            }
            """;

    private static final String FS_UP = """
            #version 150
            in vec2 uv;
            out vec4 o;
            uniform sampler2D uLow;
            uniform sampler2D uHigh;
            uniform vec2 uHalf;     // half a LOW texel
            void main() {
                vec4 l = 0.25 * (texture(uLow, uv + vec2(-uHalf.x, 0.0)) + texture(uLow, uv + vec2(uHalf.x, 0.0))
                               + texture(uLow, uv + vec2(0.0, -uHalf.y)) + texture(uLow, uv + vec2(0.0, uHalf.y)));
                o = texture(uHigh, uv) + l * 1.1;
            }
            """;

    // MOOD: colour grade. Real distance (uCur: blocks, -1 hand, SKY) for fog, view direction for sky/aurora.
    private static final String FS_MOOD = COMMON + """
            uniform sampler2D uScene;
            uniform sampler2D uCur;
            uniform mat4 uInvPvM;
            uniform vec2 uTexel;
            uniform float uTime;
            uniform float uExposure, uToe, uSat, uFogDist, uFogAmt, uSkyMix, uAurora, uAurSpill, uGlow, uVignette;
            uniform float uContrast, uEmit, uHasSky, uBright, uBloomAmt, uNight, uMoodBrightness;
            uniform sampler2D uBloom;
            uniform sampler2D uShaft;   // torch scattering before the broad bloom pyramid
            uniform vec3 uShTint, uHiTint, uFogCol, uSkyTop, uSkyHor, uAurC1, uAurC2;
            vec3 toLin(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
            vec3 toSrgb(vec3 c) {
                c = clamp(c, 0.0, 1.0);
                return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
            }
            float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            float vnoise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
            }
            uniform sampler3D uEmitVol;     // blocks around the camera: r = block light (the real light sources)
            uniform vec3 uEmitOrigin;
            uniform float uEmitOn;
            // 1 = this pixel is a light-emitting block, 0 = it is not, -1 = unknown (hand, sky, outside the scan)
            float lightBlock(vec2 q) {
                float d = texture(uCur, q).r;
                if (uEmitOn < 0.5 || d <= 0.0 || d >= SKY * 0.1) return -1.0;
                ivec3 cb = ivec3(floor(pointAt(uInvPvM, q, d + 0.03) - uEmitOrigin));
                if (any(lessThan(cb, ivec3(0))) || any(greaterThanEqual(cb, ivec3(32)))) return -1.0;
                return texelFetch(uEmitVol, cb, 0).r > 0.0 ? 1.0 : 0.0;
            }
            vec3 bright(vec2 q) {                         // bright part of a neighbour, for the glow
                vec3 c = toLin(texture(uScene, q).rgb) * uExposure;
                float l = lum(c);
                // only real light blocks glow (day or night): a lit flower is bright, but it is not a light.
                // Unknown pixels: the sky and the hand may glow; beyond the block scan only near-white highlights
                float lb = lightBlock(q);
                float k = 1.0;
                if (lb == 0.0) k = 0.0;
                else if (lb < 0.0) {
                    float d = texture(uCur, q).r;
                    if (d > 0.0 && d < SKY * 0.1) {
                        vec3 s = texture(uScene, q).rgb;
                        float mx = max(s.r, max(s.g, s.b)), mn = min(s.r, min(s.g, s.b));
                        k = smoothstep(0.75, 0.9, mn / max(mx, 1e-3));
                    }
                }
                return c * max(l - 0.12, 0.0) / (l + 1e-4) * k;
            }
            void main() {
                vec3 src = texture(uScene, uv).rgb;
                vec3 c = toLin(src);
                // like the game's brightness: only what is darker than ~mid grey changes; daylight stays
                float kb = max(uBright, 0.9 * uNight);            // nights get the game's 0 % look automatically
                c *= max(pow(clamp(lum(c) / 0.22, 1e-4, 1.0), kb), 0.28);
                float L0 = lum(c);
                // light around this pixel (1/32 res of the input): fog in a dark cave stays dark, near torches it glows
                float amb = lum(toLin(textureLod(uScene, uv, 5.0).rgb));
                vec3 fogLit = uFogCol * clamp(amb * 6.0, 0.03, 1.2);
                // the fog gets the same darkening as the scene (game-brightness curve), and at night it is moonlit
                fogLit *= max(pow(clamp(lum(fogLit) / 0.22, 1e-4, 1.0), kb), 0.28) * mix(1.0, 0.3, uNight);
                float d = texture(uCur, uv).r;
                bool sky = d >= SKY * 0.1;
                float dist = d < 0.0 ? 0.5 : min(d, 2000.0);       // hand / held item: right in front of the eye
                vec3 dir = rayDir(uInvPvM, uv);                     // world direction (camera-relative), y = up

                // exposure + shadow crush: darks sink toward black, highlights stay
                c *= uExposure;
                c *= max(pow(max(lum(c), 1e-5), uToe - 1.0), 0.2);   // deep, but never a black cut-out
                // saturation + split toning
                float g = lum(c);
                c = g + (c - g) * uSat;
                float t = clamp(lum(toSrgb(c)), 0.0, 1.0);
                c *= mix(uShTint, uHiTint, t);
                // sky: the mood's gradient by elevation (stays put when you look around)
                float elev = clamp(dir.y, 0.0, 1.0);
                vec3 skyCol = mix(uSkyHor, uSkyTop, smoothstep(0.0, 0.6, elev));
                float skyK = clamp(lum(toLin(src)) / 0.45, 0.02, 1.0);
                if (sky) c = mix(c, skyCol * skyK, uSkyMix);
                // fog by real distance
                float sigmaT = uFogAmt / max(uFogDist, 1.0);
                float cameraDistance = max(dist - 2.0, 0.0);
                float Tcamera = exp(-sigmaT * cameraDistance);
                float f = 1.0 - Tcamera;
                if (!sky) c = mix(c, fogLit, f);
                else if (uHasSky < 0.5) c = mix(c, fogLit, uFogAmt);    // Nether/End void = the farthest fog
                // aurora: curtains anchored to the sky direction
                if (uAurora > 0.0) {
                    float az = atan(dir.z, dir.x);
                    float band = 0.62 + 0.10 * sin(az * 3.0 + 1.3) + 0.05 * sin(az * 7.0 + 0.4);   // top edge, high in the sky
                    float e = band - elev;                          // > 0 below the top edge of the curtain
                    float curtain = exp(-max(e, 0.0) * 2.2) * smoothstep(-0.04, 0.0, e) * smoothstep(0.02, 0.15, elev);
                    float rays = pow(0.5 + 0.5 * sin(az * 45.0 + 4.0 * sin(az * 5.0) + uTime * 0.15), 2.0);
                    float streak = 0.6 * rays + 0.4 * vnoise(vec2(az * 25.0, uTime * 0.2));
                    float flick = 0.5 + 0.5 * vnoise(vec2(az * 2.0, uTime * 0.07));
                    vec3 col = mix(uAurC1, uAurC2, clamp(e * 5.0, 0.0, 1.0));
                    vec3 a = col * curtain * streak * flick * uAurora;
                    if (sky) c += a * 0.6;
                    else c += uAurC1 * uAurSpill * 0.08 * pow(L0, 0.7) * uAurora;
                }
                // emitters (torches, lava, lanterns) keep their light
                float warm = clamp((src.r - src.b) * 3.0, 0.0, 1.0);
                float em = clamp((L0 - 0.45) * 4.0, 0.0, 1.0) * warm * uEmit;
                float lbc = lightBlock(uv);
                if (lbc == 0.0) em = 0.0;                          // only real light blocks keep their light
                else if (lbc < 0.0 && texture(uCur, uv).r > 0.0) {   // beyond the scan: white-hot only, not lit flowers
                    float smx = max(src.r, max(src.g, src.b)), smn = min(src.r, min(src.g, src.b));
                    em *= smoothstep(0.88, 0.97, smx) * (1.0 - smoothstep(0.3, 0.55, (smx - smn) / max(smx, 1e-3)));
                }
                c = mix(c, toLin(src) * 1.2, em);
                // bloom: emitters + sun rays; fog scatters it wider and brighter
                vec4 bloomLayer = texture(uBloom, uv);
                c += bloomLayer.rgb * uBloomAmt * (1.0 + 1.5 * uFogAmt);
                // Add torch light scattered by the fog after scene/fog compositing; preserve the pixel underneath.
                float scatter = uBloomAmt > 0.5 ? 0.16 * (1.0 - exp(-texture(uShaft, uv).a / 0.16)) : 0.0;
                // Torch shafts share one combined scattering layer, so render it as neutral white.
                // Apply the player's brightness control to the shaft after fog scattering; a square
                // response makes 200% visibly stronger while leaving 100% at its current level.
                c += vec3(1.0) * scatter * uMoodBrightness * uMoodBrightness;
                // glow: bright neighbours bleed a little
                if (uGlow > 0.0) {
                    vec3 gsum = vec3(0.0);
                    for (int i = 0; i < 8; i++) {
                        float an = float(i) * 0.785398;
                        vec2 o1 = vec2(cos(an), sin(an)) * uTexel;
                        gsum += bright(uv + o1 * 6.0) + bright(uv + o1 * 14.0);
                    }
                    c += gsum / 16.0 * uGlow * 1.5;
                }
                // contrast + vignette
                vec3 s = toSrgb(c);
                s = clamp((s - 0.35) * uContrast + 0.35, 0.0, 1.0);
                vec2 q = uv - 0.5;
                s *= 1.0 - uVignette * clamp((q.x * q.x + q.y * q.y * 0.8) * 2.2, 0.0, 1.0);
                o = vec4(s, 1.0);
            }
            """;

    // shared by FX / LENS / STYLE: scene + distance sampling, surface points and normals rebuilt from the depth
    private static final String FXLIB = """
            // shared by the effect, lens and style passes: scene, distances, surface points and normals from depth
            uniform sampler2D uScene;          // copy of the screen (mipmapped)
            uniform sampler2D uCur;            // distance in blocks, -1 = hand, SKY
            uniform mat4 uInvPvM;              // screen -> camera-relative world direction
            uniform vec2 uTexel;               // 1 / screen size
            uniform float uTime;
            float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            float hash3(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }
            float vnoise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
            }
            float vnoise3(vec3 p) {
                vec3 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x), mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y);
                float b = mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x), mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y);
                return mix(a, b, f.z);
            }
            float fbm3(vec3 p) { return vnoise3(p) * 0.5 + vnoise3(p * 2.03 + 1.7) * 0.3 + vnoise3(p * 4.1 + 3.1) * 0.2; }
            vec3 toLin(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
            vec3 toSrgb(vec3 c) {
                c = clamp(c, 0.0, 1.0);
                return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
            }
            vec3 sat(vec3 c, float s) { float g = lum(c); return max(g + (c - g) * s, 0.0); }
            // give c the luminance T. Near-black pixels have no real colour (only noise): their hue fades to grey
            // instead of being multiplied up (that was the red/blue speckle under dark moods)
            vec3 setLum(vec3 c, float T) {
                float L = lum(c);
                vec3 ch = c / max(L, 0.04);
                ch = mix(vec3(1.0), min(ch, vec3(2.5)), smoothstep(0.015, 0.07, L));
                return ch * T;
            }
            vec3 S(vec2 q) { return texture(uScene, q).rgb; }
            vec3 SL(vec2 q, float l) { return textureLod(uScene, q, l).rgb; }
            float Dr(vec2 q) { return texture(uCur, q).r; }            // raw: -1 hand, SKY
            float D(vec2 q) {                                            // hand right in front, sky very far
                float d = Dr(q);
                return d < 0.0 ? 0.5 : min(d, 3000.0);
            }
            vec3 P(vec2 q, float d) { return pointAt(uInvPvM, q, d); }   // camera-relative point, world axes
            // surface normal from the distances (the smaller step on each axis: no smearing over edges), snapped to
            // the block axes when it is nearly axis-aligned (Minecraft is mostly cubes; removes far-away noise)
            vec3 normalAt(vec2 q, float dc, vec3 pc, float px) {
                vec2 sx = vec2(uTexel.x * px, 0.0), sy = vec2(0.0, uTexel.y * px);
                float dr = D(q + sx), dl = D(q - sx), du = D(q + sy), dd = D(q - sy);
                vec3 dx = abs(dr - dc) < abs(dl - dc) ? P(q + sx, dr) - pc : pc - P(q - sx, dl);
                vec3 dy = abs(du - dc) < abs(dd - dc) ? P(q + sy, du) - pc : pc - P(q - sy, dd);
                vec3 n = normalize(cross(dx, dy));
                if (dot(n, pc) > 0.0) n = -n;
                vec3 a = abs(n);
                float m = max(a.x, max(a.y, a.z));
                if (m > 0.9) n = sign(n) * step(m - 1e-4, a);
                return n;
            }
            """;

    // EFFECTS (before the mood): wet + puddle reflections, snow cover, overgrown ruins, realistic light
    private static final String FS_FX = COMMON + FXLIB + """
            // EFFECTS: world materials + light from the depth buffer, before the mood. Surfaces, normals and world
            // positions are rebuilt per pixel; reflections and sun shadows are rays marched through the depth buffer.
            uniform mat4 uPvM;                 // camera-relative world -> clip (to march rays on screen)
            uniform vec3 uCamPos;              // camera position (x, z mod 1024): puddles / moss / snow stay put in the world
            uniform vec3 uSunDir;              // toward the sun (the moon at night)
            uniform vec3 uSkyCol;              // the game's sky colour right now
            uniform float uRain, uNight, uHasSky;
            uniform float uWet, uSnow, uMoss, uReal;
            uniform sampler3D uVol;            // blocks around the camera, 32^3: r = block light, g = kind (below)
            uniform vec3 uVolOrigin;           // its min corner, camera-relative
            uniform float uVolOn;
            // 4 = water, 3 = solid block, 2 = leaves, 1 = plant / flower / torch (no collision), 0 = air, -1 = outside the scan
            float kindAt(ivec3 c) {
                if (uVolOn < 0.5 || any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, ivec3(32)))) return -1.0;
                return floor(texelFetch(uVol, c, 0).g * 5.0 + 0.5);
            }
            float solidAt(ivec3 c) { float k = kindAt(c); return k < 0.0 ? -1.0 : (abs(k - 3.0) < 0.5 ? 1.0 : 0.0); }
            // uneven snow surface: a soft height field over the block top (world xz), its slope shades the snow
            float snowH(vec2 p) { return vnoise(p * 1.4) * 0.6 + vnoise(p * 3.3 + 5.1) * 0.3 + vnoise(p * 7.0 + 2.3) * 0.1; }

            vec2 project(vec3 p, out float w) {
                vec4 c = uPvM * vec4(p, 1.0);
                w = c.w;
                return c.xy / max(c.w, 1e-4) * 0.5 + 0.5;
            }
            bool onScreen(vec2 s) { return s.x > 0.0 && s.x < 1.0 && s.y > 0.0 && s.y < 1.0; }
            // screen-space reflection: march r from p, refine the hit; hit = 0 when the ray leaves the screen
            vec3 ssr(vec3 p0, vec3 r, out float hit) {
                hit = 0.0;
                float t = 0.3, tPrev = 0.0;
                for (int i = 0; i < 22; i++) {
                    vec3 p = p0 + r * t;
                    float w;
                    vec2 s = project(p, w);
                    if (w < 0.05 || !onScreen(s)) return vec3(0.0);
                    float ds = Dr(s), dp = length(p);
                    if (ds > 0.0 && ds < dp && dp - ds < 0.5 + t * 0.2) {
                        float a = tPrev, b = t;
                        for (int k = 0; k < 4; k++) {
                            float m = 0.5 * (a + b);
                            vec3 pm = p0 + r * m;
                            vec2 sm = project(pm, w);
                            if (Dr(sm) > 0.0 && Dr(sm) < length(pm)) b = m; else a = m;
                        }
                        s = project(p0 + r * b, w);
                        vec2 e = abs(s * 2.0 - 1.0);
                        hit = 1.0 - smoothstep(0.85, 1.0, max(e.x, e.y));
                        return SL(s, 0.9);                     // glossy: a slightly blurred mirror (1.6 looked like glue)
                    }
                    tPrev = t;
                    t *= 1.28;
                }
                return vec3(0.0);
            }
            // screen-space sun shadow: anything in the depth buffer between this point and the sun
            float sunShadow(vec3 p0, vec3 n) {
                vec3 p = p0 + n * 0.06;
                float t = 0.12, sh = 0.0;
                for (int i = 0; i < 26; i++) {
                    vec3 x = p + uSunDir * t;
                    float w;
                    vec2 s = project(x, w);
                    if (w < 0.05 || !onScreen(s)) break;
                    float ds = Dr(s), dx = length(x);
                    if (ds > 0.0 && ds < dx - 0.08 && dx - ds < 1.5 + t * 0.15) {
                        vec2 e = abs(s * 2.0 - 1.0);
                        sh = 1.0 - smoothstep(0.8, 1.0, max(e.x, e.y));
                        break;
                    }
                    t *= 1.24;
                }
                return sh;
            }
            // ambient occlusion: how much of the half-sphere above the surface (1 block) is inside geometry
            float ssao(vec3 p0, vec3 n) {
                vec3 tz = abs(n.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0);
                vec3 tx = normalize(cross(tz, n)), ty = cross(n, tx);
                float rot = hash(gl_FragCoord.xy) * 6.2832, occ = 0.0;
                for (int i = 0; i < 10; i++) {
                    float fi = float(i);
                    float a = rot + fi * 2.39996, h = fract(fi * 0.618 + 0.31);
                    float rr = 0.25 + 0.95 * fract(fi * 0.381 + 0.07);
                    vec3 d = normalize(tx * cos(a) * (1.0 - h) + ty * sin(a) * (1.0 - h) + n * (h + 0.15));
                    vec3 x = p0 + n * 0.04 + d * rr;
                    float w;
                    vec2 s = project(x, w);
                    if (w < 0.05 || !onScreen(s)) continue;
                    float ds = Dr(s), dx = length(x);
                    if (ds > 0.0 && ds < dx - 0.04) occ += 1.0 - smoothstep(0.6, 1.6, dx - ds);
                }
                return occ / 10.0;
            }
            vec3 skyLin(float k) { return toLin(uSkyCol) * k; }
            vec3 skyDir(vec3 r, float k) {
                vec4 c = uPvM * vec4(r, 0.0);
                if (c.w > 1e-3) {
                    vec2 s = c.xy / c.w * 0.5 + 0.5;
                    if (onScreen(s) && Dr(s) >= 1.0e5) return toLin(S(s));
                }
                vec3 sk = toLin(uSkyCol);
                return mix(sk * 1.25 + 0.03, sk * 0.85, smoothstep(0.0, 0.6, r.y)) * k * step(-0.05, r.y);
            }
            void main() {
                vec3 c = S(uv);
                float draw = Dr(uv);
                if (draw < 0.0 || draw >= 1.0e5) { o = vec4(c, 1.0); return; }   // hand and sky untouched
                float dc = min(draw, 3000.0);
                vec3 pc = P(uv, dc);
                vec3 n = normalAt(uv, dc, pc, 1.0);
                vec3 wp = pc + uCamPos;                       // world position (repeats every 1024 blocks)
                vec3 v = pc / dc;                             // view direction
                vec3 lin = toLin(c);
                float amb = lum(toLin(SL(uv, 5.0)));          // light around here: caves stay dark
                float outside = clamp(amb * 8.0, 0.0, 1.0) * uHasSky;
                float day = uHasSky * (1.0 - uNight);
                float up = smoothstep(0.55, 0.9, n.y);
                float detail = clamp(lum(c) / max(lum(SL(uv, 2.5)), 0.02), 0.4, 1.8);   // texture detail around 1
                float mx = max(c.r, max(c.g, c.b));
                if ((wp.y > 185.0 || dc > 96.0) && lum(c) > 0.4 && min(c.r, min(c.g, c.b)) > 0.8 * mx) { o = vec4(c, 1.0); return; }  // clouds

                // the block this pixel belongs to (from the block scan), and where inside it the pixel is
                vec3 inb = pc - n * 0.01 - uVolOrigin;
                ivec3 cb = ivec3(floor(inb));
                vec3 fb = inb - vec3(cb);
                float kind = kindAt(cb);
                if (kind >= 0.0 && texelFetch(uVol, cb, 0).r > 0.0) { o = vec4(c, 1.0); return; }   // torches etc. stay as they are
                // ---- overgrown ruins: moss on tops and upper sides, grime and drip streaks, sun-bleached colour
                if (uMoss > 0.0 && kind != 1.0) {
                    float ao = ssao(pc, n);
                    float tops = smoothstep(0.2, 0.9, n.y);
                    float m = fbm3(wp * 0.35) * 0.8 + tops * 0.25 + ao * 0.5;                 // tops and corners first
                    float moss = smoothstep(0.92 - uMoss * 0.38, 1.0 - uMoss * 0.38, m) * 0.9;   // never the whole world
                    moss *= 1.0 - smoothstep(0.06, 0.16, c.b - max(c.r, c.g));                  // not on water
                    vec3 mossCol = mix(vec3(0.035, 0.065, 0.015), vec3(0.10, 0.15, 0.03), vnoise3(wp * 1.7));
                    vec3 aged = mix(lin, vec3(lum(lin)) * vec3(1.05, 1.0, 0.88), 0.15 * uMoss);
                    float streak = (1.0 - up) * smoothstep(0.55, 0.8, vnoise(vec2(dot(wp.xz, vec2(n.z, -n.x)) * 3.0, wp.y * 0.25)));
                    aged *= 1.0 - streak * 0.3 * uMoss;
                    aged *= 1.0 - ao * 0.5 * uMoss;                                           // grime in the corners
                    vec3 mossed = mossCol * (lum(lin) * 0.9 / lum(mossCol));                  // same light + texture, green
                    lin = mix(aged, mossed, moss);
                }
                // ---- snow cover on everything that faces up, patchy at low amounts, sparkles in the sun
                if (uSnow > 0.0 && kind != 1.0) {
                    float cover = smoothstep(0.35, 0.8, n.y) * smoothstep(0.95 - uSnow * 0.9, 1.0 - uSnow * 0.9, fbm3(wp * 0.35));
                    float light = clamp(lum(toLin(SL(uv, 3.5))) * 7.0, 0.04, 1.0);                   // smooth: no texture
                    vec3 snowCol = mix(vec3(0.66, 0.75, 0.97), vec3(0.97, 0.98, 1.0), clamp(light * 1.3, 0.0, 1.0)) * light;
                    // uneven, pillowy top: the slope of a soft height field lit from the sun (or the sky)
                    vec2 sp = wp.xz;
                    float e = 0.04;
                    vec2 gH = vec2(snowH(sp + vec2(e, 0.0)) - snowH(sp - vec2(e, 0.0)), snowH(sp + vec2(0.0, e)) - snowH(sp - vec2(0.0, e))) / (2.0 * e);
                    vec3 sn = normalize(vec3(-gH.x * 0.35, 1.0, -gH.y * 0.35));
                    vec3 L = day > 0.0 ? normalize(uSunDir + vec3(0.0, 0.3, 0.0)) : vec3(0.0, 1.0, 0.0);
                    snowCol *= clamp(0.95 + (dot(sn, L) - dot(vec3(0.0, 1.0, 0.0), L)) * 1.0, 0.8, 1.08);
                    float glit = step(0.996, hash3(floor(wp * 12.0))) * day * clamp(dot(n, uSunDir) * 3.0, 0.0, 1.0);
                    snowCol += glit * 1.5;
                    // fat snow: rounded pillow edges on tops, a thick lip hanging over the sides (needs the blocks)
                    if (n.y > 0.9 && solidAt(cb) > 0.5) {
                        float edge = 1.0;
                        if (solidAt(cb + ivec3(1, 0, 0)) == 0.0) edge = min(edge, 1.0 - fb.x);
                        if (solidAt(cb - ivec3(1, 0, 0)) == 0.0) edge = min(edge, fb.x);
                        if (solidAt(cb + ivec3(0, 0, 1)) == 0.0) edge = min(edge, 1.0 - fb.z);
                        if (solidAt(cb - ivec3(0, 0, 1)) == 0.0) edge = min(edge, fb.z);
                        snowCol *= mix(0.74, 1.0, sqrt(smoothstep(0.0, 0.3, edge)));
                    }
                    if (abs(n.y) < 0.1 && solidAt(cb) > 0.5 && kindAt(cb + ivec3(0, 1, 0)) < 2.0 && kindAt(cb + ivec3(0, 1, 0)) >= 0.0) {
                        vec3 topW = vec3(wp.x, floor(wp.y - n.y * 0.01) + 1.0, wp.z);
                        float ct = smoothstep(0.95 - uSnow * 0.9, 1.0 - uSnow * 0.9, fbm3(topW * 0.35));
                        float along = abs(n.x) > 0.5 ? wp.z : wp.x;
                        float th = (0.22 + 0.15 * vnoise(vec2(along * 3.0, topW.y * 7.0 + 0.5))) * ct;
                        float s = (fb.y - (1.0 - th)) / max(th, 1e-3);
                        vec3 lipCol = mix(vec3(0.66, 0.75, 0.97), vec3(0.97, 0.98, 1.0), clamp(light * 1.3, 0.0, 1.0)) * light;
                        if (s > 0.0) {
                            lin = mix(lin, lipCol * mix(0.62, 1.0, sqrt(s)), smoothstep(0.0, 0.08, s));   // rounded, curls under
                        } else {
                            lin *= mix(1.0, 0.68, smoothstep(-0.45, 0.0, s) * ct);                        // its shadow below
                        }
                    }
                    lin = mix(lin, snowCol, cover);
                    lin = mix(lin, mix(lin, vec3(lum(lin)) * vec3(0.9, 0.95, 1.1), 0.5), (1.0 - cover) * uSnow * 0.5);  // frost
                }
                if (uSnow > 0.0 && kind == 1.0) {          // grass and flowers: frosted, pale tips, not buried
                    float tip = smoothstep(0.2, 0.9, fb.y);
                    vec3 frost = vec3(lum(lin)) * vec3(0.92, 0.97, 1.08) * 1.25 + 0.04;
                    lin = mix(lin, frost, uSnow * mix(0.35, 0.75, tip));
                }
                // ---- wet: darker, deeper colours, and on the patches that would catch the sky
                //      (tops, raised texels) a blurred, rippled mirror of the real scene above them - dark trunks stay
                //      dark, sky gaps come out bright - instead of a flat white
                if (uWet > 0.0) {
                    float wetMat = uWet * (kind == 4.0 ? 0.0 : kind == 1.0 ? 0.6 : mix(0.55, 1.0, up));   // water is already wet
                    lin *= mix(1.0, 0.42, wetMat);
                    lin = sat(lin, 1.0 + 0.35 * wetMat);
                    if (kind != 1.0) {
                        float sky = max(n.y, 0.0) * 0.75 + 0.25;                                   // how much sky this face sees
                        float bump = smoothstep(1.0, 1.3, detail);                                 // raised texels catch it first
                        float blob = smoothstep(0.5, 0.64, fbm3(wp * vec3(0.9, 0.5, 0.9)));       // wet patches in the world
                        float cosv = max(dot(-v, n), 0.0);
                        float view = 0.4 + 0.6 * pow(1.0 - cosv, 2.0);                               // stronger at a glance
                        float patchW = clamp(blob * mix(0.3, 1.0, bump), 0.0, 1.0) * sky * view;    // less stuck to single texels
                        float amt = clamp(patchW * uWet * 1.3, 0.0, 0.9);
                        // a wavy surface: the mirror image wobbles and streaks a little
                        vec2 wq = wp.xz * vec2(5.0, 1.3);
                        vec3 nn = normalize(n + vec3(vnoise(wq) - 0.5, 0.0, vnoise(wq.yx + 3.7) - 0.5) * 0.09);
                        vec3 r = reflect(v, nn);
                        if (amt > 0.01) {
                            float hit;
                            vec3 rc = toLin(ssr(pc + n * 0.02, r, hit));
                            vec3 refl = mix(skyDir(r, outside), rc, hit);
                            lin = mix(lin, refl, amt);
                        }
                        float spec = pow(max(dot(r, uSunDir), 0.0), 60.0) * blob * uHasSky;         // broad sun sheen
                        lin += vec3(1.0, 0.93, 0.8) * spec * uWet * mix(1.2, 0.2, uNight) * (1.0 - uRain * 0.6);
                    }
                }
                // ---- realistic light: sun shadows, ambient occlusion, aerial haze, filmic colour, halation
                if (uReal > 0.0) {
                    float sh = 0.0;
                    if (day > 0.0 && dot(n, uSunDir) > 0.0 && outside > 0.2) sh = sunShadow(pc, n) * day * (1.0 - uRain * 0.7);
                    float ao = ssao(pc, n);
                    // the sun has a direction: faces toward it warm and bright, away from it cool sky light
                    float ndl = dot(n, uSunDir);
                    vec3 dirL = mix(vec3(1.0), mix(vec3(0.74, 0.8, 0.98), vec3(1.14, 1.06, 0.94), smoothstep(-0.15, 0.35, ndl)),
                                    day * clamp(outside * 2.0, 0.0, 1.0));
                    vec3 shade = dirL * mix(vec3(1.0), vec3(0.36, 0.42, 0.58), sh * 0.9) * (1.0 - ao * 0.7);
                    lin = mix(lin, lin * shade, uReal);
                    if (uHasSky > 0.0) {
                        float haze = (1.0 - exp(-dc / 260.0)) * 0.55;
                        lin = mix(lin, skyLin(outside) * 0.9, haze * uReal);
                    }
                    vec3 g = toSrgb(lin);
                    float t = lum(g);
                    vec3 graded = g * mix(vec3(0.92, 1.0, 1.07), vec3(1.07, 1.0, 0.9), smoothstep(0.2, 0.75, t));   // teal / warm
                    graded = mix(graded, graded * graded * (3.0 - 2.0 * graded), 0.35);                               // filmic S
                    graded = sat(graded, 1.08);
                    vec3 hb = SL(uv, 4.0);
                    graded += vec3(1.0, 0.45, 0.25) * max(lum(hb) - 0.7, 0.0) * 0.35;                                 // halation
                    lin = mix(lin, toLin(graded), uReal);
                }
                o = vec4(toSrgb(lin), 1.0);
            }
            """;

    // LENS (after the mood): depth of field / tilt-shift
    private static final String FS_LENS = COMMON + FXLIB + """
            // LENS: depth of field focused on what the crosshair looks at (the focus glides there like a camera),
            // or tilt-shift. Scatter-as-gather: every sample spreads over its own blur disc, so a blurred foreground
            // spills softly over what is behind it, and a sharp foreground is never smeared by the background.
            uniform float uDof, uTilt;
            uniform sampler2D uFocusTex;       // 1x1: smoothed focus distance
            float coc(vec2 q, float d, float focus, float maxR) {     // blur radius in pixels
                if (Dr(q) < 0.0) return 0.0;                           // the hand stays sharp
                float r = uDof * clamp(abs(1.0 / max(d, 0.1) - 1.0 / focus) * 1.0, 0.0, 1.0);   // like an eye: in 1/distance
                float band = smoothstep(0.06, 0.42, abs(q.y - 0.42));
                r = max(r, uTilt * band);
                return r * maxR;
            }
            void main() {
                float gS = max(1.0, 1.0 / (uTexel.y * 720.0));
                float maxR = 10.0 * gS;
                float R = maxR * max(uDof, uTilt);                      // search radius
                float focus = texture(uFocusTex, vec2(0.5)).r;
                float dc = D(uv);
                float rc = coc(uv, dc, focus, maxR);
                vec3 c = S(uv);
                if (R > 0.75) {
                    float lod = max(log2(R / 5.0), 0.0);
                    float rot = hash(gl_FragCoord.xy) * 6.2832;
                    vec3 bg = SL(uv, min(lod, max(log2(max(rc, 1.0) / 5.0), 0.0)));   // in focus: the pixel itself, sharp
                    float bgW = 1.0;
                    vec3 fg = vec3(0.0);
                    float fgW = 0.0, fgA = 0.0;
                    for (int i = 0; i < 32; i++) {
                        float fi = float(i);
                        float rr = sqrt((fi + 0.5) / 32.0) * R;
                        float a = fi * 2.39996 + rot;
                        vec2 q = uv + vec2(cos(a), sin(a)) * rr * uTexel;
                        float ds = D(q);
                        float rs = coc(q, ds, focus, maxR);
                        vec3 s = SL(q, lod);
                        float bok = 1.0 + 2.0 * pow(lum(s), 4.0);               // bright bokeh
                        if (ds < dc - 0.3 && rs > rc + 1.0) {                    // blurred foreground reaching here
                            float cov = smoothstep(rr - 1.0, rr + 1.0, rs);
                            fg += s * cov * bok;
                            fgW += cov * bok;
                            fgA += cov * R * R / (32.0 * max(rs * rs, 1.0));
                        } else {                                                  // same depth or behind: own blur only
                            float cov = smoothstep(rr - 1.0, rr + 1.0, min(rs, rc));
                            bg += s * cov * bok;
                            bgW += cov * bok;
                        }
                    }
                    c = bg / bgW;
                    if (fgW > 0.0) c = mix(c, fg / fgW, clamp(fgA, 0.0, 1.0));
                }
                if (uTilt > 0.0) {                                        // miniature: toy-like colour
                    c = sat(c, 1.0 + 0.35 * uTilt);
                    c = mix(c, c * c * (3.0 - 2.0 * c), 0.25 * uTilt);
                }
                o = vec4(c, 1.0);
            }
            """;

    // FOCUS (1x1): eased autofocus distance for the lens
    private static final String FS_FOCUS = COMMON + FXLIB + """
            // FOCUS (1x1): what the crosshair looks at, eased over time like a camera's autofocus (in log distance)
            uniform sampler2D uPrevFocus;
            uniform float uK;                  // 0..1 step toward the target this frame, 1 = jump
            void main() {
                float f = 1.0e9;
                for (int i = 0; i < 9; i++) {
                    vec2 o = vec2(float(i % 3) - 1.0, float(i / 3) - 1.0) * vec2(0.02, 0.035);
                    float d = Dr(vec2(0.5) + o);
                    if (d > 0.0) f = min(f, d);
                }
                float dcen = Dr(vec2(0.5));
                if (dcen > 0.0) f = dcen;                      // the crosshair itself first, its neighbours as backup
                float target = f > 1.0e8 ? 200.0 : clamp(f, 0.3, 200.0);
                float prev = texture(uPrevFocus, vec2(0.5)).r;
                if (uK >= 1.0 || !(prev > 0.0)) { o = vec4(target, 0.0, 0.0, 1.0); return; }
                o = vec4(exp(mix(log(max(prev, 0.3)), log(target), uK)), 0.0, 0.0, 1.0);
            }
            """;

    // STYLE: art styles after the mood (anime, pastel, oil, ...). Depth gives outlines that stay on silhouettes.
    private static final String FS_STYLE = COMMON + FXLIB + """
            uniform vec3 uSunDir;              // toward the sun (the moon at night), world axes
            uniform sampler2D uBloomTex;       // the glow the mood pass added (linear) ...
            uniform float uBloomK;             // ... and how strongly (0 = no glow this frame)
            uniform float uNight, uHasSky;
            uniform float uAnime, uPastel, uOil, uWater, uComic, uSketch, uNoir, uFilm, uVapor, uRetro;
            uniform float uPixel;              // 0 = off, 1 = big pixels
            uniform float uCrt;                // 0 = off, 1 = strong CRT / VHS
            float gS;                          // 1 at 720p, 1.5 at 1080p: lines and dots keep their look
            vec2 gStep;                        // neighbour step for edges (one "art pixel")
            float gBorder = 1.0;               // CRT: soft edge of the curved glass
            float gKey = 0.9;                  // brightness the cel steps are spread over (follows a dark mood)
            float bayer4(vec2 p) {
                ivec2 i = ivec2(mod(p, 4.0));
                int k = i.x + i.y * 4;
                int m[16] = int[16](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
                return (float(m[k]) + 0.5) / 16.0;
            }
            // outline strength 0..1 on the NEAR side of a depth jump (1/d is linear on flat ground: no false lines
            // on slanted terrain) + creases between blocks (weaker), fading out with distance
            float depthEdgeW(vec2 q, float dc, float crease, float width) {
                float wc = 1.0 / dc;
                vec2 sx = vec2(gStep.x * width, 0), sy = vec2(0, gStep.y * width);
                float wl = 1.0 / D(q - sx), wr = 1.0 / D(q + sx), wd = 1.0 / D(q - sy), wu = 1.0 / D(q + sy);
                float j = max(max(wc - max(wl, 1.0 / D(q - 3.0 * sx)), wc - max(wr, 1.0 / D(q + 3.0 * sx))),
                              max(wc - max(wd, 1.0 / D(q - 3.0 * sy)), wc - max(wu, 1.0 / D(q + 3.0 * sy)))) / wc;
                float sil = smoothstep(0.06, 0.18, j);
                float lh = abs(wl + wr - 2.0 * wc) / wc, lv = abs(wd + wu - 2.0 * wc) / wc;
                float x = max(lh, lv);
                float cr = smoothstep(0.02, 0.06, x) * (1.0 - smoothstep(0.12, 0.25, x)) * crease;
                return max(sil, cr) * (1.0 - smoothstep(48.0, 160.0, dc));
            }
            float depthEdge(vec2 q, float dc, float crease) { return depthEdgeW(q, dc, crease, 1.0); }
            float lumaEdge(vec2 q, float lod, float lo, float hi) {     // Sobel on a blurred level (no texture noise)
                vec2 t = gStep * 1.5;
                float a = lum(SL(q + vec2(-t.x, t.y), lod)), b = lum(SL(q + vec2(0, t.y), lod)), c = lum(SL(q + t, lod));
                float d = lum(SL(q - vec2(t.x, 0), lod)), f = lum(SL(q + vec2(t.x, 0), lod));
                float g = lum(SL(q - t, lod)), h = lum(SL(q - vec2(0, t.y), lod)), i = lum(SL(q + vec2(t.x, -t.y), lod));
                float gx = (c + 2.0 * f + i) - (a + 2.0 * d + g), gy = (a + 2.0 * b + c) - (g + 2.0 * h + i);
                return smoothstep(lo, hi, sqrt(gx * gx + gy * gy));
            }
            vec3 cel(vec3 c, float bands, float soft) {   // luminance in steps, hue kept
                float L = lum(c);
                float x = L / gKey * bands, f = fract(x);
                float q = (floor(x) + smoothstep(1.0 - 2.0 * soft - 0.02, 1.0, f)) / bands * gKey;
                return setLum(c, max(q, L * 0.45));
            }

            // Kuwahara: 4 overlapping 3x3 quadrants of a 5x5 grid, the calmest one wins -> flat areas, sharp edges
            vec3 kuwahara(vec2 q, float spread, float lod) {
                vec3 m[4] = vec3[4](vec3(0), vec3(0), vec3(0), vec3(0));
                vec3 s2[4] = vec3[4](vec3(0), vec3(0), vec3(0), vec3(0));
                vec2 st = gStep * spread;
                for (int y = -2; y <= 2; y++) for (int x = -2; x <= 2; x++) {
                    vec3 s = SL(q + vec2(x, y) * st, lod);
                    vec3 ss = s * s;
                    if (x <= 0 && y <= 0) { m[0] += s; s2[0] += ss; }
                    if (x >= 0 && y <= 0) { m[1] += s; s2[1] += ss; }
                    if (x <= 0 && y >= 0) { m[2] += s; s2[2] += ss; }
                    if (x >= 0 && y >= 0) { m[3] += s; s2[3] += ss; }
                }
                vec3 best = vec3(0);
                float bv = 1e9;
                for (int i = 0; i < 4; i++) {
                    vec3 mu = m[i] / 9.0;
                    vec3 v = s2[i] / 9.0 - mu * mu;
                    float vs = v.r + v.g + v.b;
                    if (vs < bv) { bv = vs; best = mu; }
                }
                return best;
            }
            // ANIME (Naruto Shippuden-like): one flat colour per surface, Minecraft's own face shading taken out and
            // replaced by two hard tones from the sun (lavender shadow), light pools in two steps, thick dark ink
            // ANIME (Naruto Shippuden-like): flat colour areas, Minecraft's own face shading taken out and replaced by
            // two hard tones from the sun (soft lavender shadow), light pools in two steps, thick dark ink, white clouds
            vec3 anime(vec2 q, vec3 c0, float dc, bool sky, bool hand) {
                float mx = max(c0.r, max(c0.g, c0.b)), mn = min(c0.r, min(c0.g, c0.b));
                if (sky) {                                                                 // vivid blue, wisps go white
                    vec3 c = sat(c0, 1.3);
                    return mix(c, vec3(mx), smoothstep(0.78, 0.92, mn / max(mx, 1e-3)));
                }
                float cloud = smoothstep(0.72, 0.86, mn / max(mx, 1e-3)) * smoothstep(0.28, 0.42, lum(c0))
                            * smoothstep(40.0, 80.0, dc);                                // cloud blocks: flat white
                if (cloud > 0.5) {
                    vec3 c = sat(c0, 1.2);
                    float tone = mix(0.80, 0.98, smoothstep(0.6, 0.68, lum(c0)));          // cloud: lit / shaded
                    vec3 cc = setLum(mix(vec3(1.0), c0 / max(mx, 1e-3), 0.3), tone * smoothstep(0.0, 0.5, mx));
                    c = mix(c, cc, cloud);
                    return mix(c, vec3(0.25, 0.3, 0.45) * mx, depthEdgeW(q, dc, 0.0, 1.6) * 0.6);
                }
                vec3 pc = P(q, dc);
                vec3 n = hand ? vec3(0.0, 1.0, 0.0) : normalAt(q, dc, pc, 1.0);
                // flat colour: block textures melt into one colour inside a surface, Kuwahara keeps the edges crisp
                float inner = 1.0 - max(depthEdgeW(q, dc, 0.8, 3.0), lumaEdge(q, 2.0, 0.12, 0.3));
                vec3 flatc = mix(kuwahara(q, 1.3, 1.0), SL(q, 2.2), inner * 0.85);
                // Minecraft darkens faces by direction (top 1, N/S 0.8, E/W 0.6, bottom 0.5): undo, the sun decides
                float face = 1.0;
                if (!hand) face = n.y > 0.9 ? 1.0 : n.y < -0.9 ? 0.5 : abs(n.z) > 0.9 ? 0.8 : abs(n.x) > 0.9 ? 0.6 : 1.0;
                vec3 alb = pow(min(flatc / face, vec3(1.0)), vec3(0.85)) * 1.06;
                // brightness in 3 flat steps, decided on a blurred brightness (no speckle from block textures)
                float Lb = pow(min(lum(SL(q, 1.6)) / face, 1.0), 0.85);
                float xa = Lb / gKey * 3.0;
                float La = (floor(xa) + smoothstep(0.4, 0.6, fract(xa))) / 3.0 * gKey * 0.92 + 0.04 * gKey;
                alb = setLum(alb, max(La, lum(alb) * 0.4));
                float env = lum(SL(q, 4.5));                                               // light around here
                float day = uHasSky * (1.0 - uNight) * smoothstep(0.05, 0.12, env);        // daylight reaches here
                float litSun = hand ? smoothstep(0.22, 0.28, lum(flatc)) : smoothstep(-0.03, 0.03, dot(n, uSunDir));
                float litEnv = smoothstep(0.09, 0.13, env);                                // night / cave: torch pools
                float lit = mix(litEnv, litSun, day);
                vec3 c = alb * mix(vec3(0.66, 0.65, 0.82), vec3(1.04, 1.0, 0.95), lit);
                c = sat(min(c, vec3(1.0)), 1.3);
                float e = depthEdgeW(q, dc, 0.45, 1.6);
                return mix(c, vec3(0.07, 0.05, 0.06), e * 0.95);
            }
            vec3 pastel(vec2 q, vec3 c0, float dc, bool sky) {
                vec3 c = sky ? c0 : kuwahara(q, 1.2, 0.8);
                c = pow(c, vec3(0.6));                                                               // light, airy
                c = sat(c, 1.5);
                float t = lum(c);
                c *= mix(vec3(0.93, 0.88, 1.07), vec3(1.05, 0.96, 0.97), smoothstep(0.35, 0.85, t));  // lavender / peach-pink
                c = mix(c, vec3(1.0, 0.97, 0.98), 0.1);                                             // milky
                c += SL(q, 4.0) * 0.08;
                float e = sky ? 0.0 : depthEdge(q, dc, 0.0);
                return mix(c, c * vec3(0.72, 0.66, 0.84), e * 0.55);                               // soft coloured lines
            }
            vec3 oil(vec2 q) {
                vec3 best = pow(kuwahara(q, 2.0, 1.0), vec3(0.92));
                // canvas + brush relief: light from the upper left on stretched noise
                vec2 p = gl_FragCoord.xy / gS;
                float n0 = vnoise(p * vec2(0.22, 0.16)), n1 = vnoise((p + vec2(1.5, -1.5)) * vec2(0.22, 0.16));
                float canvas = (hash(floor(p * 0.5)) - 0.5) * 0.04;
                vec3 c = sat(best, 1.22) * (1.0 + (n0 - n1) * 0.18 + canvas);
                return c;
            }
            vec3 water(vec2 q, float dc, bool sky) {
                vec2 p = gl_FragCoord.xy / gS;
                vec2 warp = (vec2(vnoise(p * 0.05), vnoise(p * 0.05 + 7.3)) - 0.5) * gStep * 10.0;   // colour bleeds
                vec3 c = pow(SL(q + warp, 2.0), vec3(0.78));
                float edge = lumaEdge(q + warp, 2.5, 0.05, 0.35);
                c *= 1.0 - edge * 0.35;                                                               // pigment pools at edges
                c *= 0.95 + 0.10 * vnoise(p * 0.5) * vnoise(p * 0.11 + 3.1);                          // granulation
                vec3 paper = vec3(0.97, 0.95, 0.90);
                c = mix(c, paper, smoothstep(0.5, 0.95, lum(c)) * 0.7 + 0.12);                       // whites = paper
                float line = sky ? 0.0 : depthEdge(q, dc, 0.0);
                return mix(sat(c, 1.1), c * 0.55, line * 0.45);                                        // light pencil
            }
            vec3 comic(vec2 q, vec3 c0, float dc, bool sky) {
                vec3 c = sky ? c0 : kuwahara(q, 1.0, 0.5);
                // the glow becomes part of the drawing: soft bloom -> flat rings of light with an ink contour
                float ring = 0.0;
                if (uBloomK > 0.0) {
                    vec3 gl = texture(uBloomTex, q).rgb * uBloomK;
                    float g = lum(gl);
                    float b = sqrt(g) * 4.0;
                    float bq = floor(b + 0.3);
                    vec3 glowFlat = gl / max(g, 1e-5) * (bq * bq / 16.0);
                    c = toSrgb(max(toLin(c) - gl, 0.0) + glowFlat * 1.15);
                    float k = floor(b + 0.5), fwb = max(fwidth(b), 1e-4);
                    ring = step(1.0, k) * (1.0 - smoothstep(0.8, 1.8, abs(b - k) / fwb));
                }
                c = sat(pow(c, vec3(0.65)), 1.45);
                if (!sky) c = cel(c, 3.0, 0.04);
                float L = lum(c);
                // halftone dots in the shadows, 45 degree screen
                vec2 p = gl_FragCoord.xy / (6.0 * gS);
                p = mat2(0.7071, -0.7071, 0.7071, 0.7071) * p;
                float r = length(fract(p) - 0.5);
                float dots = 1.0 - smoothstep(0.0, 0.08, r - sqrt(max(0.0, 0.42 - L)) * 0.7);
                c *= 1.0 - dots * 0.4;
                // flat print colours: step the colourfulness, keep the hue. (Rounding R, G, B separately flipped
                // whole areas to another hue - sand went pink - when a step boundary moved as you looked around.)
                float Lc = lum(c);
                vec3 chv = c - Lc;
                float chm = length(chv);
                c = clamp(Lc + chv * (floor(chm * 10.0 + 0.5) / 10.0) / max(chm, 1e-5), 0.0, 1.0);
                float e = max(depthEdgeW(q, dc, 0.7, 1.3), lumaEdge(q, 2.0, 0.25, 0.5) * (1.0 - smoothstep(0.35, 0.6, lum(SL(q, 3.0)))) * 0.6);
                if (sky) e = 0.0;
                e = max(e, ring * 0.85);
                return mix(c, vec3(0.03), e);
            }
            vec3 sketch(vec2 q, vec3 c0, float dc, bool sky) {
                vec2 p = gl_FragCoord.xy / gS;
                float L = lum(SL(q, 1.0));
                vec2 w = (vec2(vnoise(p * 0.02), vnoise(p * 0.02 + 5.0)) - 0.5) * 6.0;           // hand wobble
                vec2 pw = p + w;
                float sp = 5.0;
                float h1 = 1.0 - smoothstep(0.5, 1.4, abs(fract(dot(pw, vec2(0.7071, 0.7071)) / sp) - 0.5) * sp);
                float h2 = 1.0 - smoothstep(0.5, 1.4, abs(fract(dot(pw, vec2(0.7071, -0.7071)) / sp) - 0.5) * sp);
                float h3 = 1.0 - smoothstep(0.5, 1.4, abs(fract(pw.y / (sp * 0.8)) - 0.5) * sp * 0.8);
                float ink = h1 * smoothstep(0.78, 0.6, L) + h2 * smoothstep(0.55, 0.38, L) + h3 * smoothstep(0.32, 0.18, L);
                ink *= 0.55 + 0.45 * vnoise(p * 0.3);                                               // pencil pressure
                float e = sky ? 0.0 : max(depthEdge(q, dc, 0.8), lumaEdge(q, 1.8, 0.2, 0.5) * 0.6);
                ink = clamp(max(ink * 0.6, e), 0.0, 1.0);
                vec3 paper = vec3(0.96, 0.94, 0.88) * (0.97 + 0.03 * hash(floor(p)));
                vec3 tint = mix(paper, paper * mix(vec3(1.0), sat(SL(q, 2.0), 0.6) * 1.6, 0.25), 1.0);
                return mix(tint, vec3(0.16, 0.15, 0.17), ink);
            }
            vec3 noir(vec2 q, vec3 c0) {
                float L = pow(lum(c0), 0.75);
                float g = smoothstep(0.02, 0.85, L);
                g = mix(g, g * g * (3.0 - 2.0 * g), 0.7) * 0.94;                                   // S-curve, sky not blown
                vec3 c = vec3(g) * vec3(0.98, 1.0, 1.03);
                float red = smoothstep(0.12, 0.3, c0.r - max(c0.g, c0.b));                           // keep strong reds
                c = mix(c, vec3(g * 1.4, g * 0.15, g * 0.12) + c0 * vec3(0.5, 0, 0), red);
                c += (hash(gl_FragCoord.xy + fract(uTime) * 91.0) - 0.5) * 0.07;
                return c;
            }
            vec3 film(vec2 q) {
                float fr = floor(uTime * 18.0);                                                     // 18 fps flicker
                vec2 j = vec2(0.0, (hash(vec2(fr, 3.0)) - 0.5) * 0.002);
                vec3 c0 = SL(q + j, 0.8);
                float L = lum(c0);
                vec3 c = mix(vec3(0.12, 0.08, 0.04), vec3(1.0, 0.91, 0.74), pow(smoothstep(0.0, 0.85, L), 0.75));
                c *= 0.92 + 0.12 * hash(vec2(fr, 1.0));
                c += (hash(gl_FragCoord.xy * 0.5 + fr) - 0.5) * 0.10;
                float sx = hash(vec2(fr, 7.0));
                if (sx > 0.55) c *= 1.0 - (1.0 - smoothstep(0.0, 0.0015, abs(q.x - hash(vec2(fr, 9.0))))) * 0.5;  // scratch
                vec2 dp = q * vec2(1.0 / uTexel.x, 1.0 / uTexel.y) / (gS * 3.0);
                float dust = step(0.9985, hash(floor(dp) + fr));
                c = mix(c, vec3(0.05), dust * 0.8);
                vec2 v = q - 0.5;
                c *= 1.0 - dot(v, v) * 0.9;
                return c;
            }
            vec3 vapor(vec2 q, vec3 c0, bool sky) {
                float L = smoothstep(0.0, 0.9, lum(c0));
                vec3 a = vec3(0.10, 0.02, 0.22), b = vec3(0.62, 0.10, 0.65), cc = vec3(1.0, 0.36, 0.62), d = vec3(0.40, 0.95, 0.98);
                vec3 g = L < 0.33 ? mix(a, b, L / 0.33) : L < 0.66 ? mix(b, cc, (L - 0.33) / 0.33) : mix(cc, d, (L - 0.66) / 0.34);
                vec3 c = mix(c0, g, 0.7);
                if (sky) c = mix(vec3(1.0, 0.45, 0.65), vec3(0.32, 0.12, 0.55), smoothstep(0.35, 1.0, q.y)) * (0.4 + L);
                return sat(c, 1.25);
            }
            vec3 retro(vec2 q, vec3 c0, vec2 cellPos) {   // 4 greens of an old handheld, ordered dither
                float L = lum(c0) * 1.1;
                float k = clamp(floor(L * 3.0 + bayer4(cellPos) - 0.25), 0.0, 3.0);
                return k < 0.5 ? vec3(0.06, 0.22, 0.06) : k < 1.5 ? vec3(0.19, 0.38, 0.19)
                     : k < 2.5 ? vec3(0.55, 0.67, 0.06) : vec3(0.61, 0.74, 0.06);
            }

            void main() {
                gS = max(1.0, 1.0 / (uTexel.y * 720.0));
                vec2 q = uv;
                // CRT: slightly curved glass + VHS line wobble
                if (uCrt > 0.0) {
                    vec2 cc = q - 0.5;
                    q = 0.5 + cc * (1.0 + dot(cc, cc) * 0.12 * uCrt);
                    float line = floor(gl_FragCoord.y / (2.0 * gS));
                    float fr = floor(uTime * 30.0);
                    float band = smoothstep(0.06, 0.0, abs(fract(q.y * 0.7 - uTime * 0.11) - 0.5) - 0.44);
                    q.x += (hash(vec2(line, fr)) - 0.5) * uTexel.x * gS * (1.2 + 7.0 * band) * uCrt;
                    if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) { o = vec4(0, 0, 0, 1); return; }
                    gBorder = smoothstep(0.0, 0.006, min(min(q.x, 1.0 - q.x), min(q.y, 1.0 - q.y) * 1.78));
                }
                // pixelate: every style reads the scene at the centre of its big pixel
                vec2 cellPos = gl_FragCoord.xy;
                gStep = uTexel * gS;
                float lod = 0.0;
                if (uPixel > 0.0) {
                    float cell = max(2.0, floor(mix(2.0, 9.0, uPixel) * gS + 0.5));
                    vec2 px = q / uTexel;
                    cellPos = floor(px / cell);
                    q = (cellPos + 0.5) * cell * uTexel;
                    gStep = uTexel * cell;
                    lod = max(log2(cell) - 0.5, 0.0);
                }
                vec3 c0 = lod > 0.0 ? SL(q, lod) : S(q);
                gKey = clamp(lum(textureLod(uScene, vec2(0.5), 12.0).rgb) * 2.2, 0.15, 0.95);
                float dc = D(q);
                bool sky = dc >= 2999.0, hand = Dr(q) < 0.0;
                vec3 c = c0;
                if (uAnime > 0.0) c += uAnime * (anime(q, c0, dc, sky, hand) - c0);
                if (uPastel > 0.0) c += uPastel * (pastel(q, c0, dc, sky) - c0);
                if (uOil > 0.0) c += uOil * (oil(q) - c0);
                if (uWater > 0.0) c += uWater * (water(q, dc, sky) - c0);
                if (uComic > 0.0) c += uComic * (comic(q, c0, dc, sky) - c0);
                if (uSketch > 0.0) c += uSketch * (sketch(q, c0, dc, sky) - c0);
                if (uNoir > 0.0) c += uNoir * (noir(q, c0) - c0);
                if (uFilm > 0.0) c += uFilm * (film(q) - c0);
                if (uVapor > 0.0) c += uVapor * (vapor(q, c0, sky) - c0);
                if (uRetro > 0.0) c += uRetro * (retro(q, c0, cellPos) - c0);
                c = clamp(c, 0.0, 1.0);
                if (uPixel > 0.0) {                       // fewer colours, ordered dither per big pixel
                    float lv = mix(24.0, 6.0, uPixel);
                    c = floor(c * lv + bayer4(cellPos)) / lv;
                }
                if (uCrt > 0.0) {
                    vec2 off = vec2(uTexel.x * 2.0 * gS * uCrt, 0.0);
                    vec3 s0 = S(q);
                    c.r += (S(q + off).r - s0.r) * 0.8;      // colour fringes
                    c.b += (S(q - off).b - s0.b) * 0.8;
                    float scan = 0.5 + 0.5 * cos(gl_FragCoord.y * 3.14159 / (1.5 * gS));
                    c *= 1.0 - 0.28 * uCrt * scan;
                    int m = int(mod(gl_FragCoord.x / max(1.0, floor(gS + 0.5)), 3.0));          // aperture grille
                    vec3 mask = m == 0 ? vec3(1.0, 0.85, 0.85) : m == 1 ? vec3(0.85, 1.0, 0.85) : vec3(0.85, 0.85, 1.0);
                    c *= mix(vec3(1.0), mask, 0.6 * uCrt);
                    c += SL(q, 4.0) * 0.12 * uCrt;                                                 // phosphor glow
                    c += (hash(gl_FragCoord.xy + fract(uTime) * 57.0) - 0.5) * 0.06 * uCrt;
                    vec2 v = uv - 0.5;
                    c *= 1.0 - dot(v, v) * 1.2 * uCrt;
                    c *= 1.0 + 0.25 * uCrt;                  // the mask and lines darken; give it back
                }
                o = vec4(clamp(c, 0.0, 1.0) * gBorder, 1.0);
            }
            """;


    // ------------------------------------------------------------ setup

    private void ensureBase() {
        if (vao >= 0) {
            return;
        }
        vao = GL30.glGenVertexArrays();
        progWorld = program(FS_WORLD);
        progCur = program(FS_CUR);
        progMood = program(FS_MOOD);
        progStyle = program(FS_STYLE);
        progFx = program(FS_FX);
        progLens = program(FS_LENS);
        progFocus = program(FS_FOCUS);
        progBright = program(FS_BRIGHT);
        progDown = program(FS_DOWN);
        progUp = program(FS_UP);
        Restyler.LOG.info("Restyler: shaders ready");
    }

    private void ensureSize(int w, int h) {
        ensureBase();
        if (w == fw && h == fh) {
            return;
        }
        delete(worldFbo, worldTex);
        delete(curFbo, curTex);
        delete(vanFbo, vanTex);
        for (int i = 0; i < BLOOM_LEVELS; i++) {
            delete(bDownFbo[i], bDownTex[i]);
            delete(bUpFbo[i], bUpTex[i]);
            bDownFbo[i] = bDownTex[i] = bUpFbo[i] = bUpTex[i] = -1;
        }
        int[] t = target(w, h, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, false);
        vanFbo = t[0];
        vanTex = t[1];
        t = target(w, h, GL30.GL_RG32F, GL30.GL_RG, GL11.GL_FLOAT, false);
        worldFbo = t[0];
        worldTex = t[1];
        t = target(w, h, GL30.GL_R32F, GL11.GL_RED, GL11.GL_FLOAT, false);
        curFbo = t[0];
        curTex = t[1];
        for (int i = 0; i < BLOOM_LEVELS; i++) {
            bW[i] = Math.max(1, w >> (i + 1));
            bH[i] = Math.max(1, h >> (i + 1));
            t = target(bW[i], bH[i], GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT, true);
            bDownFbo[i] = t[0];
            bDownTex[i] = t[1];
            t = target(bW[i], bH[i], GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT, true);
            bUpFbo[i] = t[0];
            bUpTex[i] = t[1];
        }
        fw = w;
        fh = h;
    }

    /** Turned back on: the block volume and the eased focus are from another place / time. */
    void reset() {
        emitValid = false;
        lastLensNs = 0;
    }

    // ------------------------------------------------------------ passes

    /** WorldRenderEvents.END: remember the camera and turn the depth buffer into distances. */
    void onWorldEnd(Framebuffer main, Matrix4f proj, Matrix4f view, double x, double y, double z) {
        ensureSize(main.textureWidth, main.textureHeight);
        curCam.set(proj, view, x, y, z);
        try (Pass p = new Pass(worldFbo, 0, 0, fw, fh, progWorld)) {
            p.tex("uDepth", 0, main.getDepthAttachment());
            p.mat("uInvPv", curCam.invPv);
            p.draw();
        }
        main.beginWrite(true);
        worldReady = true;
    }

    /**
     * End of GameRenderer.renderWorld: the depth buffer was cleared and holds ONLY the hand / held item now
     * (before the GUI clears it again) -> hand mask. Hand pixels get distance -1 and are never re-projected.
     */
    void handPass(Framebuffer main) {
        if (!worldReady || main.textureWidth != fw || main.textureHeight != fh) {
            return;
        }
        try (Pass p = new Pass(curFbo, 0, 0, fw, fh, progCur)) {
            p.tex("uDepth", 0, main.getDepthAttachment());
            p.tex("uWorld", 1, worldTex);
            p.draw();
        }
        main.beginWrite(true);
        curReady = true;
    }

    /** HUD time: true if this frame's distances + hand mask are ready. */
    boolean ready(Framebuffer main) {
        return curReady && main.textureWidth == fw && main.textureHeight == fh;
    }

    void endFrame() {
        worldReady = false;
        curReady = false;
        frameNo++;
        lastBloomK = 0f;
    }

    /** Mood grade over the whole screen (after show). p = Mood.params(), emit = total mood weight. */
    void mood(Framebuffer main, float[] p, float emit, boolean hasSky, float bright, float bloom, float rays,
              float[] sunDir, float[] rayCol, float night, float torchRays) {
        if (fw <= 0 || !curCam.valid) {
            return;
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, vanFbo);
        GL30.glBlitFramebuffer(0, 0, fw, fh, 0, 0, fw, fh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._bindTexture(vanTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GlStateManager._bindTexture(0);
        boolean doBloom = bloom > 0f || (rays > 0f && hasSky) || (torchRays > 0f && p[Mood.FOG_AMT] > 0f);
        if (doBloom) {
            updateTorchLights();                      // light blocks for the bloom (and torches for the shafts)
            if (!(torchRays > 0f && p[Mood.FOG_AMT] > 0f)) lights.clear();
            bloomChain(bright, bloom, hasSky ? rays : 0f, sunDir, rayCol, p[Mood.FOG_DIST], p[Mood.FOG_AMT], torchRays);
        }
        try (Pass q = new Pass(main.fbo, 0, 0, fw, fh, progMood)) {
            q.tex("uScene", 0, vanTex);
            q.tex("uCur", 1, curTex);
            q.mat("uInvPvM", curCam.invPv);
            GL20.glUniform2f(GL20.glGetUniformLocation(progMood, "uTexel"), 1f / fw, 1f / fh);
            q.f("uTime", (System.nanoTime() % 3_600_000_000_000L) / 1e9f);
            q.f("uExposure", p[Mood.EXPOSURE]);
            q.f("uToe", 1f + 2f * p[Mood.TOE]);
            q.f("uSat", p[Mood.SAT]);
            q.v3("uShTint", p, Mood.SH_TINT);
            q.v3("uHiTint", p, Mood.HI_TINT);
            q.v3("uFogCol", p, Mood.FOG_COL);
            q.f("uFogDist", Math.max(1f, p[Mood.FOG_DIST]));
            q.f("uFogAmt", p[Mood.FOG_AMT]);
            q.v3("uSkyTop", p, Mood.SKY_TOP);
            q.v3("uSkyHor", p, Mood.SKY_HOR);
            q.f("uSkyMix", p[Mood.SKY_MIX]);
            q.f("uAurora", p[Mood.AURORA]);
            q.v3("uAurC1", p, Mood.AUR_C1);
            q.v3("uAurC2", p, Mood.AUR_C2);
            q.f("uAurSpill", p[Mood.AUR_SPILL]);
            q.f("uGlow", p[Mood.GLOW]);
            q.f("uVignette", p[Mood.VIGNETTE]);
            q.f("uContrast", p[Mood.CONTRAST]);
            q.f("uEmit", emit);
            q.f("uHasSky", hasSky ? 1f : 0f);
            q.f("uBright", bright < 1f ? (1f - bright) * 0.9f : -(bright - 1f) * 0.45f);
            q.f("uBloomAmt", doBloom ? 1f : 0f);
            q.f("uNight", night);
            q.f("uMoodBrightness", bright);
            q.tex("uBloom", 2, doBloom ? bUpTex[0] : bDownTex[0]);
            q.tex("uShaft", 3, bDownTex[0]);
            updateEmitVolume();
            bindVolume(q, progMood, "uEmitVol", "uEmitOrigin", "uEmitOn");
            q.draw();
            unbindVolume();
        }
        lastBloomK = doBloom ? 1f + 1.5f * p[Mood.FOG_AMT] : 0f;
        main.beginWrite(true);
    }

    /** Copy the screen into vanTex (+ mips): a pass can't read the framebuffer it draws on. */
    private void copyScene(Framebuffer main) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, vanFbo);
        GL30.glBlitFramebuffer(0, 0, fw, fh, 0, 0, fw, fh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._bindTexture(vanTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GlStateManager._bindTexture(0);
    }

    private void sceneUniforms(Pass q, int prog) {
        q.tex("uScene", 0, vanTex);
        q.tex("uCur", 1, curTex);
        q.mat("uInvPvM", curCam.invPv);
        GL20.glUniform2f(GL20.glGetUniformLocation(prog, "uTexel"), 1f / fw, 1f / fh);
        q.f("uTime", (System.nanoTime() % 3_600_000_000_000L) / 1e9f);
    }

    /**
     * World effects before the mood. e = Effect.amounts(); sunDir toward the sun (moon at night); skyCol = the game's
     * sky colour; rain 0..1; night 0..1.
     */
    void fx(Framebuffer main, float[] e, float[] sunDir, float[] skyCol, float rain, float night, boolean hasSky) {
        if (fw <= 0 || !curCam.valid) {
            return;
        }
        copyScene(main);
        try (Pass q = new Pass(main.fbo, 0, 0, fw, fh, progFx)) {
            sceneUniforms(q, progFx);
            q.mat("uPvM", curCam.pv);
            GL20.glUniform3f(GL20.glGetUniformLocation(progFx, "uCamPos"), (float) (curCam.x - Math.floor(curCam.x / 1024.0) * 1024.0),
                    (float) curCam.y, (float) (curCam.z - Math.floor(curCam.z / 1024.0) * 1024.0));
            q.v3("uSunDir", sunDir, 0);
            q.v3("uSkyCol", skyCol, 0);
            q.f("uRain", rain);
            q.f("uNight", night);
            q.f("uHasSky", hasSky ? 1f : 0f);
            q.f("uWet", e[Effect.WET]);
            q.f("uSnow", e[Effect.SNOW]);
            q.f("uMoss", e[Effect.MOSS]);
            q.f("uReal", e[Effect.REAL]);
            updateEmitVolume();                     // snow, wet and moss need to know which pixels are plants
            bindVolume(q, progFx, "uVol", "uVolOrigin", "uVolOn");
            q.draw();
            unbindVolume();
        }
        main.beginWrite(true);
    }

    /** Depth of field / tilt-shift after the mood. */
    void lens(Framebuffer main, float dof, float tilt) {
        if (fw <= 0 || !curCam.valid) {
            return;
        }
        copyScene(main);
        if (focusFbo[0] < 0) {
            for (int i = 0; i < 2; i++) {
                int[] t = target(1, 1, GL30.GL_R32F, GL11.GL_RED, GL11.GL_FLOAT, false);
                focusFbo[i] = t[0];
                focusTex[i] = t[1];
            }
        }
        long now = System.nanoTime();
        float dt = (now - lastLensNs) / 1e9f;
        float k = lastLensNs == 0 || dt > 0.5f ? 1f : 1f - (float) Math.exp(-dt / 0.35f);   // ~1 s to settle
        lastLensNs = now;
        int src = focusIdx, dst = 1 - focusIdx;
        try (Pass q = new Pass(focusFbo[dst], 0, 0, 1, 1, progFocus)) {
            q.tex("uCur", 1, curTex);
            q.tex("uPrevFocus", 0, focusTex[src]);
            q.f("uK", k);
            q.draw();
        }
        focusIdx = dst;
        try (Pass q = new Pass(main.fbo, 0, 0, fw, fh, progLens)) {
            sceneUniforms(q, progLens);
            q.tex("uFocusTex", 2, focusTex[dst]);
            q.f("uDof", dof);
            q.f("uTilt", tilt);
            q.draw();
        }
        main.beginWrite(true);
    }

    /** Art styles over the whole screen (after show + mood). a = Style.amounts() in Style.UNIFORMS order. */
    void style(Framebuffer main, float[] a, float[] sunDir, float night, boolean hasSky) {
        if (fw <= 0 || !curCam.valid) {
            return;
        }
        copyScene(main);
        try (Pass q = new Pass(main.fbo, 0, 0, fw, fh, progStyle)) {
            sceneUniforms(q, progStyle);
            q.v3("uSunDir", sunDir, 0);
            q.f("uNight", night);
            q.f("uHasSky", hasSky ? 1f : 0f);
            for (int i = 0; i < Style.UNIFORMS.length; i++) q.f(Style.UNIFORMS[i], a[i]);
            q.tex("uBloomTex", 2, lastBloomK > 0f ? bUpTex[0] : vanTex);
            q.f("uBloomK", lastBloomK);
            q.draw();
        }
        main.beginWrite(true);
    }

    /** Bright pass (emitters + sun rays) at half res, then blur down to 1/16 and back up into bUpTex[0]. */
    private void bindVolume(Pass q, int prog, String tex, String origin, String on) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, emitTex < 0 ? 0 : emitTex);
        GL20.glUniform1i(GL20.glGetUniformLocation(prog, tex), 4);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GL20.glUniform3f(GL20.glGetUniformLocation(prog, origin), (float) (shownOx - curCam.x),
                (float) (shownOy - curCam.y), (float) (shownOz - curCam.z));
        q.f(on, emitValid && emitTex >= 0 ? 1f : 0f);
    }

    private void unbindVolume() {
        GlStateManager._activeTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /** Scan EV/4 layers of the block volume around the camera per frame; upload when a sweep is complete. */
    private void updateEmitVolume() {
        if (volFrame == frameNo) return;              // bloom and snow both want it: once per frame
        volFrame = frameNo;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) { emitValid = false; return; }
        if (emitTex < 0) {
            emitTex = GL11.glGenTextures();
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, emitTex);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RG8, EV, EV, EV, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        }
        if (emitLayer == 0) {
            emitOx = (int) Math.floor(curCam.x) - EV / 2;
            emitOy = (int) Math.floor(curCam.y) - EV / 2;
            emitOz = (int) Math.floor(curCam.z) - EV / 2;
        }
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int end = emitLayer + EV / 4;
        for (int z = 0; z < EV; z++) for (int y = emitLayer; y < end; y++) for (int x = 0; x < EV; x++) {
            pos.set(emitOx + x, emitOy + y, emitOz + z);
            net.minecraft.block.BlockState st = mc.world.getBlockState(pos);
            int i2 = 2 * (x + EV * (y + EV * z));
            emitBuf.put(i2, (byte) (st.getLuminance() * 17));
            // kind: 4 water, 3 solid block, 2 leaves, 1 plant / flower / torch (no collision), 0 air  (stored as kind * 51)
            // (water used to count as "plant" - no collision - so snow skipped it inside this 32-block cube only)
            int kind = st.isAir() ? 0 : st.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) ? 2
                    : st.isSolidBlock(mc.world, pos) ? 3
                    : st.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER) ? 4
                    : st.getCollisionShape(mc.world, pos).isEmpty() ? 1 : 0;
            emitBuf.put(i2 + 1, (byte) (kind * 51));
        }
        emitLayer = end;
        if (emitLayer >= EV) {
            emitLayer = 0;
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, emitTex);
            GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, EV, EV, EV, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, emitBuf);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            shownOx = emitOx; shownOy = emitOy; shownOz = emitOz;
            emitValid = true;
        }
    }

    // torches within 32 blocks (16 up / down), found by a sweep spread over ~9 frames; the nearest 4 are used
    private static final int TR = 32, TRY = 16;
    private final java.util.ArrayList<float[]> torchPending = new java.util.ArrayList<>();
    private java.util.ArrayList<float[]> torchAll = new java.util.ArrayList<>();
    private int torchSlice = 0, tsx, tsy, tsz;
    // every light-emitting block in the same sweep (block corner xyz + light / 15): beyond the 32^3 block volume the
    // bloom checks the nearest FAR_MAX of these, so a torch-lit flower 20+ blocks away does not glow either
    static final int FAR_MAX = 64;
    private final java.util.ArrayList<float[]> farPending = new java.util.ArrayList<>();
    private java.util.ArrayList<float[]> farAll = new java.util.ArrayList<>();
    private final float[] farUniform = new float[4 * FAR_MAX];
    private int farCount = 0;

    /** Find actual torch blocks around the camera. Block coordinates avoid false sources from lava texture highlights. */
    private void updateTorchLights() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) { lights.clear(); torchAll.clear(); return; }
        if (torchSlice == 0) {
            tsx = (int) Math.floor(curCam.x);
            tsy = (int) Math.floor(curCam.y);
            tsz = (int) Math.floor(curCam.z);
            torchPending.clear();
            farPending.clear();
        }
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int z0 = tsz - TR + torchSlice, z1 = Math.min(z0 + 8, tsz + TR + 1);
        for (int z = z0; z < z1; z++) for (int y = tsy - TRY; y <= tsy + TRY; y++) for (int x = tsx - TR; x <= tsx + TR; x++) {
            pos.set(x, y, z);
            net.minecraft.block.BlockState st = mc.world.getBlockState(pos);
            int lum = st.getLuminance();
            if (lum > 0) farPending.add(new float[]{x, y, z, lum / 15f});
            var block = st.getBlock();
            float intensity = block == Blocks.TORCH || block == Blocks.WALL_TORCH ? 1.0f
                    : block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH ? 0.65f
                    : block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH ? 0.75f : 0f;
            if (intensity > 0f) torchPending.add(new float[]{x + 0.5f, y + 0.72f, z + 0.5f, intensity});
        }
        torchSlice += 8;
        if (torchSlice > 2 * TR) {
            torchSlice = 0;
            torchAll = new java.util.ArrayList<>(torchPending);
            farAll = new java.util.ArrayList<>(farPending);
        }
        // the nearest light blocks, camera-relative, for the bloom's check beyond the block volume
        java.util.ArrayList<float[]> fl = new java.util.ArrayList<>();
        for (float[] f : farAll) {
            float dx = f[0] + 0.5f - (float) curCam.x, dy = f[1] + 0.5f - (float) curCam.y, dz = f[2] + 0.5f - (float) curCam.z;
            fl.add(new float[]{f[0], f[1], f[2], f[3], dx * dx + dy * dy + dz * dz});
        }
        fl.sort((a, b) -> Float.compare(a[4], b[4]));
        farCount = Math.min(FAR_MAX, fl.size());
        for (int i = 0; i < farCount; i++) {
            float[] f = fl.get(i);
            farUniform[4 * i] = (float) (f[0] - curCam.x);
            farUniform[4 * i + 1] = (float) (f[1] - curCam.y);
            farUniform[4 * i + 2] = (float) (f[2] - curCam.z);
            farUniform[4 * i + 3] = f[3];
        }
        // every frame: the nearest torches to where the camera is now, fading out smoothly toward 40 blocks
        java.util.ArrayList<float[]> near = new java.util.ArrayList<>();
        for (float[] t : torchAll) {
            float dx = t[0] - (float) curCam.x, dy = t[1] - (float) curCam.y, dz = t[2] - (float) curCam.z;
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float fade = Math.max(0f, Math.min(1f, (40f - d) / 8f));
            if (fade > 0f) near.add(new float[]{t[0], t[1], t[2], t[3] * fade, d});
        }
        near.sort((a, b) -> Float.compare(a[4], b[4]));
        lights.clear();
        for (int i = 0; i < Math.min(MAX_LIGHTS, near.size()); i++) lights.add(near.get(i));
    }

    private void bloomChain(float bright, float bloom, float rays, float[] sunDir, float[] rayCol, float fogDist, float fogAmt,
                            float torchRays) {
        // sun (or moon) on screen: project the direction with the current camera
        float sunVis = 0f, su = 0.5f, sv = 0.5f;
        if (rays > 0f && sunDir != null) {
            org.joml.Vector4f c = new org.joml.Vector4f(sunDir[0], sunDir[1], sunDir[2], 0f).mul(curCam.pv);
            if (c.w > 1e-4f) {
                su = c.x / c.w * 0.5f + 0.5f;
                sv = c.y / c.w * 0.5f + 0.5f;
                float off = Math.max(Math.max(-su, su - 1f), Math.max(-sv, sv - 1f));   // > 0: outside the screen
                sunVis = Math.max(0f, 1f - Math.max(0f, off) / 0.6f) * Math.max(0f, Math.min(1f, sunDir[1] * 6f + 0.3f));
            }
        }
        try (Pass q = new Pass(bDownFbo[0], 0, 0, bW[0], bH[0], progBright)) {
            q.tex("uScene", 0, vanTex);
            q.tex("uCur", 1, curTex);
            GL20.glUniform2f(GL20.glGetUniformLocation(progBright, "uSunUv"), su, sv);
            q.f("uSunVis", sunVis);
            q.f("uRays", rays);
            q.v3("uRayCol", rayCol, 0);
            q.f("uBright", bright);
            q.f("uBloomE", bloom * 4f);
            q.f("uFogDist", Math.max(fogDist, 1f));
            q.f("uFogAmt", fogAmt);
            q.mat("uInvPv", curCam.invPv);
            float[] torch = new float[4 * MAX_LIGHTS];
            int count = 0;
            for (float[] l : lights) {
                torch[4 * count] = l[0] - (float) curCam.x;
                torch[4 * count + 1] = l[1] - (float) curCam.y;
                torch[4 * count + 2] = l[2] - (float) curCam.z;
                torch[4 * count + 3] = l[3];
                if (++count == MAX_LIGHTS) break;
            }
            GL20.glUniform4fv(GL20.glGetUniformLocation(progBright, "uTorch"), torch);
            q.i("uTorchCount", count);
            q.f("uTorchRays", torchRays);
            updateEmitVolume();
            bindVolume(q, progBright, "uEmitVol", "uEmitOrigin", "uEmitOn");
            GL20.glUniform4fv(GL20.glGetUniformLocation(progBright, "uFar"), farUniform);
            q.i("uFarCount", farCount);
            q.draw();
            unbindVolume();
        }
        for (int i = 1; i < BLOOM_LEVELS; i++) {
            try (Pass q = new Pass(bDownFbo[i], 0, 0, bW[i], bH[i], progDown)) {
                q.tex("uSrc", 0, bDownTex[i - 1]);
                GL20.glUniform2f(GL20.glGetUniformLocation(progDown, "uHalf"), 0.5f / bW[i - 1], 0.5f / bH[i - 1]);
                q.draw();
            }
        }
        // up: level i = down[i] + blurred(up[i+1])  (the coarsest level starts from down)
        int lowTex = bDownTex[BLOOM_LEVELS - 1];
        for (int i = BLOOM_LEVELS - 2; i >= 0; i--) {
            try (Pass q = new Pass(bUpFbo[i], 0, 0, bW[i], bH[i], progUp)) {
                q.tex("uLow", 0, lowTex);
                q.tex("uHigh", 1, bDownTex[i]);
                GL20.glUniform2f(GL20.glGetUniformLocation(progUp, "uHalf"), 0.5f / bW[i + 1], 0.5f / bH[i + 1]);
                q.draw();
            }
            lowTex = bUpTex[i];
        }
    }

    // ------------------------------------------------------------ GL helpers

    /** One full-viewport triangle into fbo with program prog; restores the game's GL state on close. */
    private final class Pass implements AutoCloseable {
        private final int prog;
        private int units = 0;
        private final boolean blend;
        private final boolean depth;

        Pass(int fbo, int x, int y, int w, int h, int prog) {
            this.prog = prog;
            blend = GL11.glIsEnabled(GL11.GL_BLEND);
            depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GlStateManager._viewport(x, y, w, h);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            BufferRenderer.reset();               // the game's cached vertex array is no longer bound after this
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glUseProgram(prog);
        }

        void tex(String name, int unit, int tex) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
            GlStateManager._bindTexture(tex);
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, name), unit);
            units = Math.max(units, unit + 1);
        }

        void mat(String name, Matrix4f m) {
            try (MemoryStack s = MemoryStack.stackPush()) {
                FloatBuffer fb = s.mallocFloat(16);
                m.get(fb);
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(prog, name), false, fb);
            }
        }

        void v3(String name, float[] p, int at) {
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, name), p[at], p[at + 1], p[at + 2]);
        }

        void f(String name, float v) {
            GL20.glUniform1f(GL20.glGetUniformLocation(prog, name), v);
        }

        void i(String name, int v) {
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, name), v);
        }

        void draw() {
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        }

        @Override
        public void close() {
            for (int u = units - 1; u >= 0; u--) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + u);
                GlStateManager._bindTexture(0);
            }
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._glUseProgram(0);
            GlStateManager._glBindVertexArray(0);
            if (blend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            if (depth) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
        }
    }

    private static int program(String fs) {
        int vs = shader(GL20.GL_VERTEX_SHADER, VS);
        int f = shader(GL20.GL_FRAGMENT_SHADER, fs);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, f);
        GL30.glBindFragDataLocation(p, 0, "o");
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("Restyler shader link failed: " + GL20.glGetProgramInfoLog(p));
        }
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(f);
        return p;
    }

    private static int shader(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("Restyler shader compile failed: " + GL20.glGetShaderInfoLog(s));
        }
        return s;
    }

    /** texture + framebuffer; linear = bilinear filter (colour), else nearest (distances). */
    private static int[] target(int w, int h, int internal, int format, int type, boolean linear) {
        int tex = GL11.glGenTextures();
        GlStateManager._bindTexture(tex);
        int filter = linear ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, (ByteBuffer) null);
        GlStateManager._bindTexture(0);
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Restyler: framebuffer incomplete, status " + status);
        }
        return new int[]{fbo, tex};
    }

    private static void delete(int fbo, int tex) {
        if (fbo >= 0) {
            GL30.glDeleteFramebuffers(fbo);
        }
        if (tex >= 0) {
            GlStateManager._bindTexture(0);
            GL11.glDeleteTextures(tex);
        }
    }
}
