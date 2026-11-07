package dev.swipixel.restyler;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Installs the exact CLIP encoder and tokenizer bundled with this release. */
final class ClipFetcher {
    private static final String[][] FILES = {
            {"clip_text.onnx", "80862df02759e72e3e6a6c0ddc5c21292c80e63fc51a93397e6effc32384afac"},
            {"tokenizer.json", "6d9109cc838977f3ca94a379eec36aecc7c807e1785cd729660ca2fc0171fb35"}
    };

    private ClipFetcher() {}

    static void download(Path dir, java.util.function.Consumer<String> progress) throws Exception {
        Files.createDirectories(dir);
        for (String[] file : FILES) {
            String name = file[0];
            Path destination = dir.resolve(name);
            Path partial = dir.resolve(name + ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = ClipFetcher.class.getResourceAsStream("/assets/restyler/clip/" + name)) {
                if (in == null) throw new IllegalStateException("Bundled CLIP asset is missing: " + name);
                try (OutputStream out = Files.newOutputStream(partial)) {
                    byte[] buffer = new byte[1 << 16];
                    long done = 0;
                    int last = -1;
                    for (int n; (n = in.read(buffer)) >= 0;) {
                        if (n == 0) continue;
                        out.write(buffer, 0, n);
                        digest.update(buffer, 0, n);
                        done += n;
                        if (name.endsWith(".onnx")) {
                            int pct = (int) Math.min(100, done * 100 / 253_839_768L);
                            if (pct / 10 != last / 10) {
                                last = pct;
                                progress.accept("Restyler: installing CLIP " + pct + "%");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Files.deleteIfExists(partial);
                throw e;
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!file[1].equals(actual)) {
                Files.deleteIfExists(partial);
                throw new IllegalStateException("Bundled CLIP checksum mismatch for " + name);
            }
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
