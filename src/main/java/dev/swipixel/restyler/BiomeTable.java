package dev.swipixel.restyler;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * biomes.txt: per-biome look lines. Plain Java, no Minecraft.
 *
 *   desert          = golden_hour 50, apocalyptic 40     base (set)
 *   desert @noon    = apocalyptic +15, vibrant -10       condition (offset / set)
 *   desert ~        = golden_hour 15, misty_morning 0..15  variation ranges
 *   * @thunder      = stormy +40                         * = every biome without its own line
 *   plains          = "soft green morning"               prompt
 *
 * ";" starts a comment anywhere outside quotes, "#" at the start of a line too. Biome ids without "minecraft:".
 * Bad lines become warnings ("file:line: ...") and are skipped; parsing never throws.
 */
final class BiomeTable {

    enum Cond {
        DAWN, NOON, DUSK, NIGHT, RAIN, SNOW, THUNDER;

        final String key = name().toLowerCase(Locale.ROOT);

        static Cond of(String s) {
            for (Cond c : values()) if (c.key.equals(s)) return c;
            return null;
        }
    }

    static final String STAR = "*";

    static final class Entry {
        final String id;
        LookSpec base, var;
        final EnumMap<Cond, LookSpec> conds = new EnumMap<>(Cond.class);
        int line;                 // first line that mentions the biome (for warnings)

        Entry(String id, int line) {
            this.id = id;
            this.line = line;
        }
    }

    /** Parsed key: biome + (condition | variation | base). */
    record Key(String biome, Cond cond, boolean var) {
        String suffix() {
            return cond != null ? " @" + cond.key : var ? " ~" : "";
        }
    }

    final String fileName;
    final Map<String, Entry> entries = new LinkedHashMap<>();
    final List<String> warnings = new ArrayList<>();

    private BiomeTable(String fileName) {
        this.fileName = fileName;
    }

    static BiomeTable empty() {
        return new BiomeTable("(none)");
    }

    Entry get(String id) {
        return entries.get(id);
    }

    Entry star() {
        return entries.get(STAR);
    }

    // ------------------------------------------------------------ parsing

    static BiomeTable parse(List<String> lines, String fileName) {
        BiomeTable t = new BiomeTable(fileName);
        Set<Key> seen = new LinkedHashSet<>();
        for (int n = 0; n < lines.size(); n++) {
            String raw = stripComment(lines.get(n)).strip();
            if (raw.isEmpty()) continue;
            int lineNo = n + 1;
            int eq = raw.indexOf('=');
            if (eq < 0) {
                t.warn(lineNo, "missing '=' in '" + raw + "'");
                continue;
            }
            Key k = parseKey(raw.substring(0, eq));
            if (k == null) {
                t.warn(lineNo, "bad key '" + raw.substring(0, eq).strip() + "' (expected <biome>, <biome> @<condition> or <biome> ~)");
                continue;
            }
            LookSpec.Mode mode = k.cond != null ? LookSpec.Mode.COND : k.var ? LookSpec.Mode.VARIATION : LookSpec.Mode.BASE;
            LookSpec spec;
            try {
                spec = LookSpec.parse(raw.substring(eq + 1), mode);
            } catch (IllegalArgumentException e) {
                t.warn(lineNo, k.biome + k.suffix() + ": " + e.getMessage() + " - line ignored");
                continue;
            }
            if (!seen.add(k)) t.warn(lineNo, k.biome + k.suffix() + " is defined twice, this later line wins");
            Entry e = t.entries.computeIfAbsent(k.biome, id -> new Entry(id, lineNo));
            if (k.cond != null) e.conds.put(k.cond, spec);
            else if (k.var) e.var = spec;
            else e.base = spec;
        }
        return t;
    }

    private void warn(int line, String msg) {
        warnings.add(fileName + ":" + line + ": " + msg);
    }

    /** Text before a ';' outside quotes; '#' only as the first character (vibes.txt style). */
    static String stripComment(String line) {
        if (line.stripLeading().startsWith("#")) return "";
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') q = !q;
            else if (c == ';' && !q) return line.substring(0, i);
        }
        return line;
    }

    /** "desert", "desert @noon", "desert@noon", "desert ~", "* @thunder", "mymod:ash_waste". Null if malformed. */
    static Key parseKey(String keyText) {
        String s = keyText.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        Cond cond = null;
        boolean var = false;
        int at = s.indexOf('@');
        if (at >= 0) {
            cond = Cond.of(s.substring(at + 1).strip());
            if (cond == null) return null;
            s = s.substring(0, at).strip();
        } else if (s.endsWith("~")) {
            var = true;
            s = s.substring(0, s.length() - 1).strip();
        }
        if (s.isEmpty() || s.contains(" ")) return null;
        String id = normId(s);
        if (!id.equals(STAR) && !id.matches("[a-z0-9_.\\-/]+(:[a-z0-9_.\\-/]+)?")) return null;
        return new Key(id, cond, var);
    }

    /** Biome ids are written without "minecraft:"; modded ones keep their namespace. */
    static String normId(String id) {
        String s = id.strip().toLowerCase(Locale.ROOT);
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    // ------------------------------------------------------------ editing (for /vibe biome ...)

    private static Key keyOf(String line) {
        String raw = stripComment(line).strip();
        int eq = raw.indexOf('=');
        return eq < 0 ? null : parseKey(raw.substring(0, eq));
    }

    private static String valueOf(String line) {
        String raw = stripComment(line).strip();
        int eq = raw.indexOf('=');
        return eq < 0 ? "" : raw.substring(eq + 1).strip();
    }

    /** Replaces the line with this key (or adds it after the biome's last line / at the end). Returns new lines. */
    static List<String> setLine(List<String> lines, Key key, String value) {
        List<String> out = new ArrayList<>(lines);
        String text = key.biome + key.suffix() + " = " + value.strip();
        int last = -1;
        for (int i = 0; i < out.size(); i++) {
            Key k = keyOf(out.get(i));
            if (k == null) continue;
            if (k.equals(key)) {
                out.set(i, text);
                return out;
            }
            if (k.biome.equals(key.biome)) last = i;
        }
        if (last >= 0) out.add(last + 1, text);
        else out.add(text);
        return out;
    }

    /** Puts the built-in lines of one biome back into the config copy (in place of its current lines). */
    static List<String> resetBiome(List<String> config, List<String> builtin, String biome) {
        List<String> out = new ArrayList<>();
        int insertAt = -1;
        for (String l : config) {
            Key k = keyOf(l);
            if (k != null && k.biome.equals(biome)) {
                if (insertAt < 0) insertAt = out.size();
                continue;
            }
            out.add(l);
        }
        List<String> mine = new ArrayList<>();
        for (String l : builtin) {
            Key k = keyOf(l);
            if (k != null && k.biome.equals(biome)) mine.add(l);
        }
        if (insertAt < 0) insertAt = out.size();
        out.addAll(insertAt, mine);
        return out;
    }

    /** Biomes whose lines in the config copy differ from the built-in table. */
    static Set<String> customBiomes(List<String> config, List<String> builtin) {
        Map<String, Map<Key, String>> a = linesByBiome(config), b = linesByBiome(builtin);
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Map<Key, String>> e : a.entrySet()) {
            if (!Objects.equals(e.getValue(), b.get(e.getKey()))) out.add(e.getKey());
        }
        for (String id : b.keySet()) if (!a.containsKey(id)) out.add(id);
        return out;
    }

    private static Map<String, Map<Key, String>> linesByBiome(List<String> lines) {
        Map<String, Map<Key, String>> m = new LinkedHashMap<>();
        for (String l : lines) {
            Key k = keyOf(l);
            if (k == null) continue;
            m.computeIfAbsent(k.biome, x -> new LinkedHashMap<>()).put(k, valueOf(l).replaceAll("\\s+", " "));
        }
        return m;
    }
}
