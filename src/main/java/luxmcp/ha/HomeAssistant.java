package luxmcp.ha;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional read-only access to the Home Assistant recorder via the REST API, used for trends. The
 * live values always come from the controller itself.
 */
public final class HomeAssistant {

    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public HomeAssistant(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Markdown summary + downsampled samples of one entity's history. */
    @SuppressWarnings("unchecked")
    public String history(String entityId, double hours, int maxPoints) throws IOException, InterruptedException {
        Instant start = Instant.now().minus(Duration.ofMinutes(Math.round(hours * 60)));
        String url = baseUrl + "/api/history/period/" + DateTimeFormatter.ISO_INSTANT.format(start)
                + "?filter_entity_id=" + URLEncoder.encode(entityId, StandardCharsets.UTF_8)
                + "&minimal_response&no_attributes";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new IOException("Home Assistant rejected the token (HTTP " + resp.statusCode() + ")");
        }
        if (resp.statusCode() != 200) {
            throw new IOException("Home Assistant returned HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
        }
        List<List<Map<String, Object>>> data = McpJsonDefaults.getMapper().readValue(resp.body(),
                new TypeRef<List<List<Map<String, Object>>>>() {
                });
        if (data == null || data.isEmpty() || data.get(0).isEmpty()) {
            return "No history for " + entityId + " in the last " + fmt(hours) + " h. Check the entity id (Developer tools > States in HA).";
        }
        List<Map<String, Object>> states = data.get(0);
        List<double[]> numeric = new ArrayList<>(); // [epochSeconds, value]
        List<String[]> textual = new ArrayList<>();
        for (Map<String, Object> st : states) {
            String state = String.valueOf(st.get("state"));
            String when = st.get("last_changed") != null ? String.valueOf(st.get("last_changed")) : String.valueOf(st.get("last_updated"));
            long epoch;
            try {
                epoch = OffsetDateTime.parse(when).toEpochSecond();
            } catch (Exception e) {
                continue;
            }
            try {
                numeric.add(new double[]{epoch, Double.parseDouble(state)});
            } catch (NumberFormatException e) {
                textual.add(new String[]{when, state});
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("# ").append(entityId).append(" - last ").append(fmt(hours)).append(" h, ").append(states.size()).append(" state changes\n");
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
        if (!numeric.isEmpty()) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
            for (double[] p : numeric) {
                min = Math.min(min, p[1]);
                max = Math.max(max, p[1]);
                sum += p[1];
            }
            b.append(String.format(Locale.ROOT, "min %.2f, max %.2f, mean %.2f (of %d numeric samples), first %s, last %s\n\n",
                    min, max, sum / numeric.size(), numeric.size(), fmtNum(numeric.get(0)[1]), fmtNum(numeric.get(numeric.size() - 1)[1])));
            b.append("| time | value |\n|---|---|\n");
            int step = Math.max(1, (int) Math.ceil(numeric.size() / (double) maxPoints));
            for (int i = 0; i < numeric.size(); i += step) {
                double[] p = numeric.get(i);
                b.append("| ").append(tf.format(Instant.ofEpochSecond((long) p[0]))).append(" | ").append(fmtNum(p[1])).append(" |\n");
            }
            double[] last = numeric.get(numeric.size() - 1);
            if ((numeric.size() - 1) % step != 0) {
                b.append("| ").append(tf.format(Instant.ofEpochSecond((long) last[0]))).append(" | ").append(fmtNum(last[1])).append(" |\n");
            }
        }
        if (!textual.isEmpty()) {
            b.append("\nNon-numeric states (").append(textual.size()).append("):\n");
            int step = Math.max(1, (int) Math.ceil(textual.size() / (double) maxPoints));
            for (int i = 0; i < textual.size(); i += step) {
                String[] t = textual.get(i);
                b.append("- ").append(t[0]).append(": ").append(t[1]).append('\n');
            }
        }
        return b.toString();
    }

    private static String fmtNum(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.2f", d);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
