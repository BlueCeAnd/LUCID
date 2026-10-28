import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Makes a Modrinth-sized copy (< 500 MiB) of a mod jar that bundles ONNX Runtime GPU (LUCID Weather, Authenticraft).
 *
 *   java -Xmx3g StripNatives.java build\libs\lucid-weather-1.0.0.jar
 *     -> build\libs\modrinth\lucid-weather-1.0.0.jar   (the original stays untouched)
 *
 * Only the nested ONNX Runtime jar changes: the CUDA / TensorRT GPU providers for NON-Windows platforms are removed.
 *   Windows: everything stays (CPU + CUDA) -> works exactly as before.
 *   Linux:   the CPU runtime stays -> still runs (on the CPU). The mods only set up CUDA on Windows anyway.
 * Afterwards the output is re-read completely (every CRC checked), the Windows natives are checked to be byte-identical
 * to the original, and the size is checked. Any problem -> exit code 1, no output file left behind.
 */
public class StripNatives {

    static final long LIMIT = 500L * 1024 * 1024;   // Modrinth: 500 MiB per version
    static final String NATIVE = "ai/onnxruntime/native/";
    static final String[] WIN_REQUIRED = {"onnxruntime.dll", "onnxruntime4j_jni.dll", "onnxruntime_providers_shared.dll",
            "onnxruntime_providers_cuda.dll"};

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java -Xmx3g StripNatives.java <mod.jar> [out.jar]");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        Path out = args.length > 1 ? Path.of(args[1]) : in.toAbsolutePath().getParent().resolve("modrinth").resolve(in.getFileName());
        Files.createDirectories(out.toAbsolutePath().getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try {
            int nested = rewriteOuter(in, tmp);
            if (nested == 0) {
                fail("no nested ONNX Runtime jar (META-INF/jars/*onnxruntime*.jar) found in " + in);
            }
            verify(in, tmp);
            long size = Files.size(tmp);
            System.out.printf(Locale.ROOT, "%n%s: %.1f MiB -> %.1f MiB%n", in.getFileName(),
                    Files.size(in) / 1048576.0, size / 1048576.0);
            if (size >= LIMIT) {
                fail(String.format(Locale.ROOT, "still %.1f MiB, Modrinth allows < 500 MiB", size / 1048576.0));
            }
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("OK -> " + out);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Non-Windows GPU providers: the only thing removed. */
    static boolean drop(String name) {
        if (!name.startsWith(NATIVE) || name.endsWith("/")) {
            return false;
        }
        String rest = name.substring(NATIVE.length());          // e.g. linux-x64/libonnxruntime_providers_cuda.so
        if (rest.startsWith("win-")) {
            return false;
        }
        String file = rest.substring(rest.indexOf('/') + 1).toLowerCase(Locale.ROOT);
        return file.contains("providers_cuda") || file.contains("providers_tensorrt");
    }

    static boolean isOrtJar(String name) {
        return name.startsWith("META-INF/jars/") && name.endsWith(".jar") && name.toLowerCase(Locale.ROOT).contains("onnxruntime");
    }

    static int rewriteOuter(Path in, Path out) throws IOException {
        int nested = 0;
        try (ZipFile z = new ZipFile(in.toFile()); ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(out))) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                byte[] data;
                try (InputStream is = z.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                if (isOrtJar(e.getName())) {
                    System.out.println("nested jar: " + e.getName());
                    data = rewriteInner(data);
                    nested++;
                }
                put(zo, e, data);
            }
        }
        return nested;
    }

    static byte[] rewriteInner(byte[] jar) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(jar.length);
        long removed = 0;
        try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(jar)); ZipOutputStream zo = new ZipOutputStream(bo)) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) {
                byte[] data = zi.readAllBytes();
                if (drop(e.getName())) {
                    removed += data.length;
                    System.out.printf(Locale.ROOT, "  removed %-60s %7.1f MiB%n", e.getName(), data.length / 1048576.0);
                    continue;
                }
                put(zo, e, data);
            }
        }
        System.out.printf(Locale.ROOT, "  removed %.1f MiB uncompressed in total%n", removed / 1048576.0);
        return bo.toByteArray();
    }

    /** Same name, time and compression method as the original entry. */
    static void put(ZipOutputStream zo, ZipEntry src, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(src.getName());
        if (src.getTime() != -1) {
            e.setTime(src.getTime());
        }
        if (src.getMethod() == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
        } else {
            e.setMethod(ZipEntry.DEFLATED);
        }
        zo.putNextEntry(e);
        zo.write(data);
        zo.closeEntry();
    }

    /** Re-read everything; same entries except the dropped ones; Windows natives byte-identical; required ones present. */
    static void verify(Path orig, Path made) throws IOException {
        try (ZipFile a = new ZipFile(orig.toFile()); ZipFile b = new ZipFile(made.toFile())) {
            List<String> names = new ArrayList<>();
            a.stream().forEach(e -> names.add(e.getName()));
            for (String n : names) {
                ZipEntry eb = b.getEntry(n);
                if (eb == null) {
                    fail("missing in output: " + n);
                }
                byte[] da = read(a, a.getEntry(n)), db = read(b, eb);   // reading checks every CRC
                if (isOrtJar(n)) {
                    verifyInner(da, db);
                } else if (!java.util.Arrays.equals(da, db)) {
                    fail("changed: " + n);
                }
            }
            if (b.size() != names.size()) {
                fail("entry count differs");
            }
        }
        System.out.println("verified: every entry re-read, only non-Windows GPU providers removed");
    }

    static void verifyInner(byte[] orig, byte[] made) throws IOException {
        java.util.Map<String, String> a = unzip(orig), b = unzip(made);   // entry -> SHA-256 (keeps memory low)
        int win = 0;
        for (var en : a.entrySet()) {
            String n = en.getKey();
            if (drop(n)) {
                if (b.containsKey(n)) {
                    fail("not removed: " + n);
                }
                continue;
            }
            if (!b.containsKey(n) || !en.getValue().equals(b.get(n))) {
                fail("nested entry missing or changed: " + n);
            }
            if (n.startsWith(NATIVE + "win-x64/")) {
                win++;
            }
        }
        if (b.size() + count(a) != a.size()) {
            fail("nested entry count differs");
        }
        for (String r : WIN_REQUIRED) {
            if (!b.containsKey(NATIVE + "win-x64/" + r)) {
                fail("Windows native missing: " + r);
            }
        }
        System.out.println("  Windows natives kept and byte-identical: " + win + " files");
    }

    static int count(java.util.Map<String, String> a) {
        int c = 0;
        for (String n : a.keySet()) {
            if (drop(n)) {
                c++;
            }
        }
        return c;
    }

    static java.util.Map<String, String> unzip(byte[] jar) throws IOException {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(jar))) {
            byte[] buf = new byte[1 << 16];
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) {
                java.security.MessageDigest md;
                try {
                    md = java.security.MessageDigest.getInstance("SHA-256");
                } catch (java.security.NoSuchAlgorithmException x) {
                    throw new IOException(x);
                }
                for (int n; (n = zi.read(buf)) > 0; ) {   // ZipInputStream checks the CRC at the end of each entry
                    md.update(buf, 0, n);
                }
                m.put(e.getName(), java.util.HexFormat.of().formatHex(md.digest()));
            }
        }
        return m;
    }

    static byte[] read(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream is = z.getInputStream(e)) {
            return is.readAllBytes();
        }
    }

    static void fail(String msg) {
        System.err.println("FAILED: " + msg);
        throw new IllegalStateException(msg);
    }
}
