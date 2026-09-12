package luxmcp.docs;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Full-text index over the manuals in a directory (PDF, .txt, .md).
 * <p>
 * Every page is split into chunks of roughly a thousand characters, labelled with the document,
 * page number and the nearest heading taken from the document's own table of contents (the
 * manuals have no PDF outline). Search is a small BM25-like scorer with German umlaut folding and
 * prefix/compound matching, which is plenty for a few hundred pages. The Luxtronik error table
 * ("Fehlerdiagnose / Fehlermeldungen") is additionally parsed into a code -> text map.
 */
public final class DocIndex {

    private static final Logger log = LoggerFactory.getLogger(DocIndex.class);
    private static final int CHUNK_CHARS = 1100;
    private static final Pattern TOC_LINE = Pattern.compile("^(.{3,}?)\\s*\\.{2,}\\s*(\\d{1,3})\\s*$");
    private static final Pattern PAGE_HEADER = Pattern.compile("^(\\d{1,3}|(\\d{1,3}\\s*)?Technische Änderungen vorbehalten.*)$");
    private static final Pattern ERROR_LINE = Pattern.compile("^(7\\d\\d)\\s+(\\S.*)$");

    /** One searchable piece of a document. */
    public record Chunk(String doc, int page, String heading, String text) {
        public String label() {
            return doc + " p." + page + (heading.isEmpty() ? "" : " - " + heading);
        }
    }

    public record Hit(Chunk chunk, double score) {
    }

    private final List<Chunk> chunks = new ArrayList<>();
    private final List<Map<String, Integer>> chunkTerms = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private final Map<String, List<String>> pages = new LinkedHashMap<>(); // doc -> page texts
    private final Map<Integer, String> errorCodes = new TreeMap<>();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Path dir;

    private DocIndex(Path dir) {
        this.dir = dir;
    }

    /** Start indexing {@code dir} on a background thread; tool calls block on {@link #await()}. */
    public static DocIndex loadAsync(Path dir) {
        DocIndex idx = new DocIndex(dir);
        Thread t = new Thread(() -> {
            try {
                idx.load();
                idx.ready.complete(null);
            } catch (Throwable e) {
                idx.ready.completeExceptionally(e);
            }
        }, "docs-indexer");
        t.setDaemon(true);
        t.start();
        return idx;
    }

    /** Synchronous variant for tests. */
    public static DocIndex load(Path dir) throws IOException {
        DocIndex idx = new DocIndex(dir);
        idx.load();
        idx.ready.complete(null);
        return idx;
    }

    public void await() throws IOException {
        try {
            ready.join();
        } catch (Exception e) {
            throw new IOException("document index failed to load from " + dir + ": " + e.getCause(), e);
        }
    }

    public Path dir() {
        return dir;
    }

    public List<String> documents() {
        return new ArrayList<>(pages.keySet());
    }

    public int pageCount(String doc) {
        List<String> p = pages.get(doc);
        return p == null ? 0 : p.size();
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** Full text of one page (1-based) without the running page header, or null. */
    public String page(String doc, int page) {
        List<String> p = pages.get(resolveDoc(doc));
        if (p == null || page < 1 || page > p.size()) {
            return null;
        }
        return stripHeader(p.get(page - 1));
    }

    /** Resolve a document by exact name or unique case-insensitive substring. */
    public String resolveDoc(String name) {
        if (name == null) {
            return null;
        }
        if (pages.containsKey(name)) {
            return name;
        }
        String n = name.toLowerCase(Locale.ROOT);
        String found = null;
        for (String d : pages.keySet()) {
            if (d.toLowerCase(Locale.ROOT).contains(n)) {
                if (found != null) {
                    return null; // ambiguous
                }
                found = d;
            }
        }
        return found;
    }

    /** Manual text for a Luxtronik error code (7xx), or null if the manuals do not describe it. */
    public String errorInfo(int code) {
        return errorCodes.get(code);
    }

    public Map<Integer, String> errorCodes() {
        return Collections.unmodifiableMap(errorCodes);
    }

    // ------------------------------------------------------------------ search

    public List<Hit> search(String query, int limit) {
        List<String> q = new ArrayList<>(tokens(query).keySet());
        if (q.isEmpty()) {
            return List.of();
        }
        int n = chunks.size();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> terms = chunkTerms.get(i);
            double score = 0;
            int matched = 0;
            for (String qt : q) {
                int tf = 0;
                int df = 0;
                for (Map.Entry<String, Integer> e : terms.entrySet()) {
                    if (matches(qt, e.getKey())) {
                        tf += e.getValue();
                        df = Math.max(df, docFreq.getOrDefault(e.getKey(), 1));
                    }
                }
                if (tf > 0) {
                    matched++;
                    double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                    score += idf * (1 + Math.log(tf));
                    if (fold(chunks.get(i).heading()).contains(qt)) {
                        score += idf; // heading bonus
                    }
                }
            }
            if (matched > 0) {
                // reward chunks that contain all query terms
                score *= (double) matched / q.size();
                hits.add(new Hit(chunks.get(i), score));
            }
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.subList(0, Math.min(limit, hits.size()));
    }

    private static boolean matches(String queryTerm, String chunkTerm) {
        if (chunkTerm.equals(queryTerm)) {
            return true;
        }
        if (queryTerm.length() >= 4 && chunkTerm.startsWith(queryTerm)) {
            return true;
        }
        return queryTerm.length() >= 6 && chunkTerm.contains(queryTerm);
    }

    // ------------------------------------------------------------------ loading

    private void load() throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("not a directory: " + dir);
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        long t0 = System.currentTimeMillis();
        for (Path f : files) {
            String name = f.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            List<String> pageTexts;
            if (lower.endsWith(".pdf")) {
                pageTexts = extractPdf(f);
            } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                pageTexts = List.of(Files.readString(f, StandardCharsets.UTF_8));
            } else {
                continue;
            }
            String doc = name.replaceFirst("\\.[^.]+$", "");
            addDocument(doc, pageTexts);
        }
        for (Map<String, Integer> terms : chunkTerms) {
            for (String t : terms.keySet()) {
                docFreq.merge(t, 1, Integer::sum);
            }
        }
        log.info("indexed {} document(s), {} pages, {} chunks, {} error codes from {} in {} ms", pages.size(),
                pages.values().stream().mapToInt(List::size).sum(), chunks.size(), errorCodes.size(), dir,
                System.currentTimeMillis() - t0);
    }

    private static List<String> extractPdf(Path f) throws IOException {
        List<String> out = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(f.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int n = pdf.getNumberOfPages();
            for (int p = 1; p <= n; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                out.add(stripper.getText(pdf).replace("\r\n", "\n").replace('\r', '\n'));
            }
        }
        return out;
    }

    void addDocument(String doc, List<String> pageTexts) {
        pages.put(doc, pageTexts);
        TreeMap<Integer, String> toc = parseToc(pageTexts);
        for (int p = 1; p <= pageTexts.size(); p++) {
            String text = stripHeader(pageTexts.get(p - 1));
            Map.Entry<Integer, String> h = toc.floorEntry(p);
            String heading = h == null ? "" : h.getValue();
            for (String part : split(text)) {
                chunks.add(new Chunk(doc, p, heading, part));
                chunkTerms.add(tokens(part + " " + heading));
            }
        }
        parseErrorTable(pageTexts);
    }

    /** "TITLE ........ 17" lines from the first pages, keyed by page (several titles per page joined). */
    private static TreeMap<Integer, String> parseToc(List<String> pageTexts) {
        TreeMap<Integer, String> toc = new TreeMap<>();
        int scan = Math.min(6, pageTexts.size());
        String pending = "";
        for (int p = 0; p < scan; p++) {
            for (String line : pageTexts.get(p).split("\\R")) {
                line = line.strip();
                Matcher m = TOC_LINE.matcher(line);
                if (m.matches()) {
                    String title = (pending + " " + m.group(1)).strip().replaceAll("\\s+", " ");
                    int page = Integer.parseInt(m.group(2));
                    toc.merge(page, title, (a, b) -> a + " / " + b);
                    pending = "";
                } else if (!line.isEmpty() && line.length() < 70 && !line.contains("|") && !line.matches("\\d+")) {
                    pending = line; // possible first half of a wrapped title
                } else {
                    pending = "";
                }
            }
        }
        return toc;
    }

    private static String stripHeader(String pageText) {
        StringBuilder b = new StringBuilder();
        int lineNo = 0;
        for (String line : pageText.split("\\R")) {
            lineNo++;
            if (lineNo <= 3 && PAGE_HEADER.matcher(line.strip()).matches()) {
                continue;
            }
            b.append(line.replace(" -\n", "").stripTrailing()).append('\n');
        }
        return b.toString().replaceAll("(\\S) -\\n(\\p{Ll})", "$1$2").strip(); // re-join hyphenated words
    }

    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        if (text.isEmpty()) {
            return parts;
        }
        String[] lines = text.split("\\R");
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            if (cur.length() + line.length() > CHUNK_CHARS && cur.length() > CHUNK_CHARS / 2) {
                parts.add(cur.toString().strip());
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) {
            parts.add(cur.toString().strip());
        }
        return parts;
    }

    private void parseErrorTable(List<String> pageTexts) {
        for (String pageText : pageTexts) {
            if (!pageText.contains("Abhilfe") && !pageText.contains("Fehlerdiagnose")) {
                continue;
            }
            Integer code = null;
            StringBuilder body = new StringBuilder();
            for (String line : pageText.split("\\R")) {
                String l = line.strip();
                Matcher m = ERROR_LINE.matcher(l);
                if (m.matches()) {
                    flushError(code, body);
                    code = Integer.parseInt(m.group(1));
                    body.setLength(0);
                    body.append(m.group(2));
                } else if (code != null) {
                    if (PAGE_HEADER.matcher(l).matches() || l.startsWith("Nr. Anzeige")) {
                        continue;
                    }
                    body.append(' ').append(l);
                }
            }
            flushError(code, body);
        }
    }

    private void flushError(Integer code, StringBuilder body) {
        if (code == null) {
            return;
        }
        String text = body.toString().replaceAll("\\s+", " ").strip();
        if (text.length() > 15) {
            errorCodes.merge(code, text, (a, b) -> a.length() >= b.length() ? a : b);
        }
    }

    // ------------------------------------------------------------------ text utils

    static Map<String, Integer> tokens(String text) {
        Map<String, Integer> m = new HashMap<>();
        if (text == null) {
            return m;
        }
        for (String t : fold(text).split("[^a-z0-9]+")) {
            if (t.length() >= 2) {
                m.merge(t, 1, Integer::sum);
            }
        }
        return m;
    }

    static String fold(String s) {
        String x = s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return x;
    }
}
