package dev.swipixel.restyler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLIP's byte-level BPE tokenizer (same as Hugging Face CLIPTokenizer for openai/clip-vit-base-patch32), taken from
 * LUCID Spectacular. Files: vocab.json + merges.txt, or tokenizer.json, next to clip_text.onnx.
 * encode(text) -> input_ids [77] and attention_mask [77]: start token, tokens, end token, padded with the end token.
 */
final class ClipTokenizer {

    static final int LEN = 77;
    private static final Pattern PAT = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|\\p{L}+|\\p{N}|[^\\s\\p{L}\\p{N}]+",
            Pattern.CASE_INSENSITIVE);

    private final Map<String, Integer> vocab = new HashMap<>();
    private final Map<String, Integer> ranks = new HashMap<>();
    private final String[] byteEnc = new String[256];
    private final Map<String, String[]> cache = new HashMap<>();
    private final int bos;
    private final int eos;

    /** Reads vocab.json + merges.txt, or tokenizer.json (what newer transformers save). */
    static ClipTokenizer load(Path dir) throws IOException {
        if (Files.exists(dir.resolve("vocab.json")) && Files.exists(dir.resolve("merges.txt"))) {
            return new ClipTokenizer(dir.resolve("vocab.json"), dir.resolve("merges.txt"));
        }
        return new ClipTokenizer(dir.resolve("tokenizer.json"));
    }

    static boolean present(Path dir) {
        return Files.exists(dir.resolve("tokenizer.json"))
                || (Files.exists(dir.resolve("vocab.json")) && Files.exists(dir.resolve("merges.txt")));
    }

    ClipTokenizer(Path vocabJson, Path mergesTxt) throws IOException {
        try (Reader r = Files.newBufferedReader(vocabJson, StandardCharsets.UTF_8)) {
            readVocab(JsonParser.parseReader(r).getAsJsonObject());
        }
        int rank = 0;
        for (String line : Files.readAllLines(mergesTxt, StandardCharsets.UTF_8)) {
            if (line.startsWith("#version") || line.isBlank()) {
                continue;
            }
            ranks.put(line.trim(), rank++);
        }
        bos = vocab.get("<|startoftext|>");
        eos = vocab.get("<|endoftext|>");
        initBytes();
    }

    ClipTokenizer(Path tokenizerJson) throws IOException {
        try (Reader r = Files.newBufferedReader(tokenizerJson, StandardCharsets.UTF_8)) {
            JsonObject model = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("model");
            readVocab(model.getAsJsonObject("vocab"));
            int rank = 0;
            for (com.google.gson.JsonElement m : model.getAsJsonArray("merges")) {
                if (m.isJsonArray()) {          // ["a", "b"]
                    ranks.put(m.getAsJsonArray().get(0).getAsString() + " " + m.getAsJsonArray().get(1).getAsString(), rank++);
                } else {                        // "a b"
                    ranks.put(m.getAsString(), rank++);
                }
            }
        }
        bos = vocab.get("<|startoftext|>");
        eos = vocab.get("<|endoftext|>");
        initBytes();
    }

    private void readVocab(JsonObject o) {
        for (String k : o.keySet()) {
            vocab.put(k, o.get(k).getAsInt());
        }
    }

    private void initBytes() {
        // GPT-2 / CLIP bytes_to_unicode
        List<Integer> bs = new ArrayList<>();
        for (int b = '!'; b <= '~'; b++) bs.add(b);
        for (int b = 0xA1; b <= 0xAC; b++) bs.add(b);
        for (int b = 0xAE; b <= 0xFF; b++) bs.add(b);
        List<Integer> cs = new ArrayList<>(bs);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n++);
            }
        }
        for (int i = 0; i < bs.size(); i++) {
            byteEnc[bs.get(i)] = new String(Character.toChars(cs.get(i)));
        }
    }

    /** Returns {input_ids, attention_mask}, each long[1][77]. */
    long[][][] encode(String text) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        List<Integer> ids = new ArrayList<>();
        ids.add(bos);
        Matcher m = PAT.matcher(t);
        while (m.find()) {
            StringBuilder u = new StringBuilder();
            for (byte b : m.group().getBytes(StandardCharsets.UTF_8)) {
                u.append(byteEnc[b & 0xFF]);
            }
            for (String piece : bpe(u.toString())) {
                Integer id = vocab.get(piece);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        if (ids.size() > LEN - 1) {
            ids = ids.subList(0, LEN - 1);
        }
        ids.add(eos);
        long[][] inputIds = new long[1][LEN];
        long[][] mask = new long[1][LEN];
        for (int i = 0; i < LEN; i++) {
            inputIds[0][i] = i < ids.size() ? ids.get(i) : eos;
            mask[0][i] = i < ids.size() ? 1 : 0;
        }
        return new long[][][]{inputIds, mask};
    }

    private String[] bpe(String token) {
        String[] hit = cache.get(token);
        if (hit != null) {
            return hit;
        }
        List<String> word = new ArrayList<>();
        int[] cps = token.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            String c = new String(Character.toChars(cps[i]));
            word.add(i == cps.length - 1 ? c + "</w>" : c);
        }
        while (word.size() > 1) {
            int best = Integer.MAX_VALUE;
            int at = -1;
            for (int i = 0; i < word.size() - 1; i++) {
                Integer r = ranks.get(word.get(i) + " " + word.get(i + 1));
                if (r != null && r < best) {
                    best = r;
                    at = i;
                }
            }
            if (at < 0) {
                break;
            }
            String first = word.get(at);
            String second = word.get(at + 1);
            List<String> merged = new ArrayList<>();
            for (int i = 0; i < word.size(); i++) {
                if (i < word.size() - 1 && word.get(i).equals(first) && word.get(i + 1).equals(second)) {
                    merged.add(first + second);
                    i++;
                } else {
                    merged.add(word.get(i));
                }
            }
            word = merged;
        }
        String[] out = word.toArray(new String[0]);
        cache.put(token, out);
        return out;
    }
}
