package dev.swipixel.restyler;

import java.util.Locale;

/**
 * One look line's value (the part after "="), parsed. Plain Java, no Minecraft.
 *
 *   BASE       "golden_hour 50, apocalyptic 40"  plain N = set, unnamed sliders = 0; +N / -N = offset on the layer below.
 *              A line with only offsets is invalid. "prompt" = set to what Vibe resolves.
 *   COND       "@noon" lines: +N / -N offsets scaled by the condition weight; plain N sets that slider
 *              (lerp toward N by the weight); unnamed sliders untouched. "prompt" = offsets.
 *   VARIATION  "~" lines: "golden_hour 15" = +-15 %, "misty_morning 0..15" = 0..+15 %, "+15" = 0..15, "-15" = -15..0.
 *   OFFSET     zones in offset mode: every number is an offset, "prompt" = offsets.
 *
 * Values are percent in the text, 0..1 (or -1..1 for offsets) in here.
 */
final class LookSpec {

    enum Mode { BASE, COND, VARIATION, OFFSET }

    static final byte NONE = 0, SET = 1, ADD = 2;

    /** Text -> slider vector (0..1), or null while it is not available (pending or unresolvable). */
    interface Prompts {
        float[] get(String prompt);
    }

    final Mode mode;
    final String source;           // the value as written (for messages, /vibe why, writing back)
    final String prompt;           // non-null: a prompt in quotes
    final byte[] op = new byte[Slots.N];
    final float[] val = new float[Slots.N];
    final float[] lo = new float[Slots.N], hi = new float[Slots.N];
    final boolean[] var = new boolean[Slots.N];

    private LookSpec(Mode mode, String source, String prompt) {
        this.mode = mode;
        this.source = source;
        this.prompt = prompt;
    }

    boolean isPrompt() {
        return prompt != null;
    }

    /** Parses the value of a line. Throws IllegalArgumentException with a readable message. */
    static LookSpec parse(String value, Mode mode) {
        String v = value.strip();
        if (v.startsWith("\"")) {
            if (v.length() < 2 || !v.endsWith("\"")) throw new IllegalArgumentException("prompt needs a closing quote");
            String p = v.substring(1, v.length() - 1).strip();
            if (p.isEmpty()) throw new IllegalArgumentException("empty prompt \"\"");
            if (mode == Mode.VARIATION) throw new IllegalArgumentException("a ~ line needs slider ranges, not a prompt");
            return new LookSpec(mode, v, p.toLowerCase(Locale.ROOT));
        }
        LookSpec s = new LookSpec(mode, v, null);
        boolean anySet = false;
        for (String part : v.split(",")) {
            part = part.strip();
            if (part.isEmpty()) continue;
            String[] t = part.split("\\s+");
            if (t.length != 2) throw new IllegalArgumentException("expected '<slider> <value>', got '" + part + "'");
            int i = Slots.index(t[0]);
            if (i < 0) throw new IllegalArgumentException("unknown slider '" + t[0] + "'");
            String num = t[1];
            if (mode == Mode.VARIATION) {
                float a, b;
                int dots = num.indexOf("..");
                if (dots > 0) {
                    a = number(num.substring(0, dots), part);
                    b = number(num.substring(dots + 2), part);
                } else if (num.startsWith("+")) {
                    a = 0f; b = number(num.substring(1), part);
                } else if (num.startsWith("-")) {
                    a = -number(num.substring(1), part); b = 0f;
                } else {
                    b = Math.abs(number(num, part)); a = -b;
                }
                s.lo[i] = Math.min(a, b) / 100f;
                s.hi[i] = Math.max(a, b) / 100f;
                s.var[i] = true;
                continue;
            }
            boolean signed = num.startsWith("+") || num.startsWith("-");
            float x = number(signed && num.startsWith("+") ? num.substring(1) : num, part) / 100f;
            if (mode == Mode.OFFSET || signed) {
                s.op[i] = ADD;
            } else {
                s.op[i] = SET;
                anySet = true;
            }
            s.val[i] = x;
        }
        if (mode == Mode.BASE) {
            boolean anyAdd = false;
            for (byte o : s.op) anyAdd |= o == ADD;
            if (anyAdd && !anySet) throw new IllegalArgumentException("a base line with only offsets (+/-) is invalid");
            for (int i = 0; i < Slots.N; i++) if (s.op[i] == NONE) s.op[i] = SET;    // unnamed sliders = 0
        }
        return s;
    }

    private static float number(String s, String part) {
        try {
            float f = Float.parseFloat(s.strip());
            if (!Float.isFinite(f)) throw new NumberFormatException();
            return f;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number in '" + part + "'");
        }
    }

    /** True if the text parses as a slider line (used to tell commands' slider lines from prompts). */
    static boolean isSliderLine(String text, Mode mode) {
        String t = text.strip();
        if (t.isEmpty() || t.startsWith("\"")) return false;
        try {
            parse(t, mode);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True if every value in a slider line is signed (+N / -N): zones then pick offset mode. */
    static boolean onlyOffsets(String text) {
        String t = text.strip();
        if (t.isEmpty() || t.startsWith("\"")) return false;
        for (String part : t.split(",")) {
            String[] p = part.strip().split("\\s+");
            if (p.length != 2 || !(p[1].startsWith("+") || p[1].startsWith("-"))) return false;
        }
        return true;
    }

    /**
     * Applies this line to look (in place) with weight w (1 = fully). SET: look = lerp(look, value, w);
     * ADD: look += w * value. Returns false (look untouched) if the prompt isn't resolved.
     */
    boolean apply(float[] look, float w, Prompts prompts) {
        if (mode == Mode.VARIATION) throw new IllegalStateException("apply on a ~ line");
        if (w <= 0f) return true;
        if (prompt != null) {
            float[] p = prompts == null ? null : prompts.get(prompt);
            if (p == null) return false;
            for (int i = 0; i < Slots.N; i++) {
                if (mode == Mode.BASE) look[i] += (p[i] - look[i]) * w;
                else look[i] += w * p[i];
            }
            return true;
        }
        for (int i = 0; i < Slots.N; i++) {
            if (op[i] == SET) look[i] += (val[i] - look[i]) * w;
            else if (op[i] == ADD) look[i] += w * val[i];
        }
        return true;
    }

    /** The sliders a BASE line sets above 0 (for the automatic +-10 % variation). Null if a prompt isn't ready. */
    boolean[] nonZero(Prompts prompts) {
        boolean[] nz = new boolean[Slots.N];
        if (prompt != null) {
            float[] p = prompts == null ? null : prompts.get(prompt);
            if (p == null) return null;
            for (int i = 0; i < Slots.N; i++) nz[i] = p[i] > 1e-4f;
        } else {
            for (int i = 0; i < Slots.N; i++) nz[i] = op[i] == SET && val[i] > 1e-4f;
        }
        return nz;
    }

    /** Automatic variation: +-10 % on the given sliders. */
    static LookSpec autoVariation(boolean[] on) {
        LookSpec s = new LookSpec(Mode.VARIATION, "(auto +-10 % on the base's sliders)", null);
        for (int i = 0; i < Slots.N; i++) {
            if (on[i]) { s.var[i] = true; s.lo[i] = -0.10f; s.hi[i] = 0.10f; }
        }
        return s;
    }

    @Override
    public String toString() {
        return source;
    }
}
