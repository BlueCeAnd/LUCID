package dev.swipixel.restyler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * Player-placed look volumes (like Unity post-processing volumes), stored per world in config/restyler/zones/<salt>.json.
 * Plain Java + Gson, no Minecraft.
 *
 * {"zones": [{"name": "spawn", "shape": "sphere", "center": [0, 64, 0], "radius": 20, "blend": 8, "priority": 0,
 *             "look": "golden_hour 60", "mode": "set", "fade": "time", "lines": ["@night = frozen +20"]}]}
 */
final class Zones {

    static final class Zone {
        String name;
        boolean box;
        double cx, cy, cz, radius;                    // sphere
        double x1, y1, z1, x2, y2, z2;                // box (min / max corners)
        double blend = 8;
        int priority = 0;
        String look = "";                             // as written: slider line or "prompt"
        boolean offset = false;                       // mode: set (default) or offset
        String fade = null;                           // time | hard | crossfade (null = the zones layer's setting)
        List<String> lines = new ArrayList<>();       // optional "@cond = ..." lines
        // parsed
        LookSpec spec;
        final EnumMap<BiomeTable.Cond, LookSpec> conds = new EnumMap<>(BiomeTable.Cond.class);

        /** Distance outside the shape (<= 0 inside). */
        double distance(double x, double y, double z) {
            if (box) {
                double dx = Math.max(Math.max(x1 - x, 0), x - x2);
                double dy = Math.max(Math.max(y1 - y, 0), y - y2);
                double dz = Math.max(Math.max(z1 - z, 0), z - z2);
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            double dx = x - cx, dy = y - cy, dz = z - cz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz) - radius;
        }

        float weight(double x, double y, double z) {
            return LookMath.zoneWeight(distance(x, y, z), blend);
        }

        /** Parses look + lines; returns the problem or null. */
        String compile() {
            conds.clear();
            spec = null;
            try {
                spec = LookSpec.parse(look, offset ? LookSpec.Mode.OFFSET : LookSpec.Mode.BASE);
            } catch (IllegalArgumentException e) {
                return "zone '" + name + "': " + e.getMessage();
            }
            for (String l : lines) {
                String raw = BiomeTable.stripComment(l).strip();
                if (raw.isEmpty()) continue;
                int eq = raw.indexOf('=');
                String k = eq < 0 ? "" : raw.substring(0, eq).strip().toLowerCase(Locale.ROOT);
                BiomeTable.Cond c = k.startsWith("@") ? BiomeTable.Cond.of(k.substring(1).strip()) : null;
                if (c == null) return "zone '" + name + "': bad line '" + l + "' (expected @<condition> = ...)";
                try {
                    conds.put(c, LookSpec.parse(raw.substring(eq + 1), LookSpec.Mode.COND));
                } catch (IllegalArgumentException e) {
                    return "zone '" + name + "' " + k + ": " + e.getMessage();
                }
            }
            return null;
        }

        String shapeText() {
            return box ? String.format(Locale.ROOT, "box %.0f %.0f %.0f .. %.0f %.0f %.0f", x1, y1, z1, x2, y2, z2)
                    : String.format(Locale.ROOT, "sphere at %.1f %.1f %.1f r=%.1f", cx, cy, cz, radius);
        }
    }

    private Zones() {}

    /** Ascending priority; equal priorities keep their file order. */
    static void sort(List<Zone> zones) {
        zones.sort(Comparator.comparingInt(z -> z.priority));
    }

    static List<Zone> load(Path file, List<String> warnings) {
        List<Zone> out = new ArrayList<>();
        if (file == null || !Files.exists(file)) return out;
        String fn = "zones/" + file.getFileName();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonArray arr = root.has("zones") ? root.getAsJsonArray("zones") : new JsonArray();
            int n = 0;
            for (JsonElement el : arr) {
                n++;
                try {
                    JsonObject o = el.getAsJsonObject();
                    Zone z = new Zone();
                    z.name = o.get("name").getAsString();
                    z.box = "box".equals(str(o, "shape", "sphere"));
                    if (z.box) {
                        double[] a = vec(o, "min"), b = vec(o, "max");
                        z.x1 = Math.min(a[0], b[0]); z.y1 = Math.min(a[1], b[1]); z.z1 = Math.min(a[2], b[2]);
                        z.x2 = Math.max(a[0], b[0]); z.y2 = Math.max(a[1], b[1]); z.z2 = Math.max(a[2], b[2]);
                    } else {
                        double[] c = vec(o, "center");
                        z.cx = c[0]; z.cy = c[1]; z.cz = c[2];
                        z.radius = o.get("radius").getAsDouble();
                    }
                    if (o.has("blend")) z.blend = Math.max(0, o.get("blend").getAsDouble());
                    if (o.has("priority")) z.priority = o.get("priority").getAsInt();
                    z.look = str(o, "look", "");
                    z.offset = "offset".equalsIgnoreCase(str(o, "mode", "set"));
                    z.fade = o.has("fade") ? o.get("fade").getAsString().toLowerCase(Locale.ROOT) : null;
                    if (o.has("lines")) for (JsonElement l : o.getAsJsonArray("lines")) z.lines.add(l.getAsString());
                    String err = z.compile();
                    if (err != null) {
                        warnings.add(fn + ": " + err + " - zone ignored");
                        continue;
                    }
                    out.add(z);
                } catch (Exception e) {
                    warnings.add(fn + ": zone #" + n + " is malformed (" + e.getMessage() + ") - ignored");
                }
            }
        } catch (Exception e) {
            warnings.add(fn + ": unreadable (" + e.getMessage() + ") - no zones loaded");
        }
        sort(out);
        return out;
    }

    static void save(Path file, List<Zone> zones) throws Exception {
        JsonArray arr = new JsonArray();
        for (Zone z : zones) {
            JsonObject o = new JsonObject();
            o.addProperty("name", z.name);
            o.addProperty("shape", z.box ? "box" : "sphere");
            if (z.box) {
                o.add("min", arr(z.x1, z.y1, z.z1));
                o.add("max", arr(z.x2, z.y2, z.z2));
            } else {
                o.add("center", arr(z.cx, z.cy, z.cz));
                o.addProperty("radius", z.radius);
            }
            o.addProperty("blend", z.blend);
            o.addProperty("priority", z.priority);
            o.addProperty("look", z.look);
            o.addProperty("mode", z.offset ? "offset" : "set");
            if (z.fade != null) o.addProperty("fade", z.fade);
            if (!z.lines.isEmpty()) {
                JsonArray l = new JsonArray();
                for (String s : z.lines) l.add(s);
                o.add("lines", l);
            }
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("zones", arr);
        Gson g = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            g.toJson(root, w);
        }
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    private static double[] vec(JsonObject o, String k) {
        JsonArray a = o.getAsJsonArray(k);
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    private static JsonArray arr(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(Math.round(d * 100.0) / 100.0);
        return a;
    }
}
