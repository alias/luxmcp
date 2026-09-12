package luxmcp.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import luxmcp.docs.DocIndex;
import luxmcp.ha.HomeAssistant;
import luxmcp.lux.HeatPump;
import luxmcp.lux.RegisterDef;
import luxmcp.lux.RegisterKind;
import luxmcp.lux.Snapshot;
import luxmcp.lux.WritePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The MCP tool surface. Every tool returns Markdown text; errors are returned as tool results with
 * {@code isError=true} and an actionable message rather than as protocol errors.
 */
public final class LuxtronikTools {

    private static final Logger log = LoggerFactory.getLogger(LuxtronikTools.class);

    private final HeatPump pump;
    private final HomeAssistant homeAssistant; // may be null
    private final DocIndex docs; // may be null

    public LuxtronikTools(HeatPump pump, HomeAssistant homeAssistant, DocIndex docs) {
        this.pump = pump;
        this.homeAssistant = homeAssistant;
        this.docs = docs;
    }

    public List<SyncToolSpecification> specifications() {
        List<SyncToolSpecification> specs = new ArrayList<>();
        specs.add(tool("luxtronik_get_status",
                "Curated overview of the heat pump: operating state, modes, all key temperatures (flow, return, outdoor, "
                        + "hot water, brine in/out), refrigerant pressures, heating curve and hot water settings, error memory "
                        + "summary and run-time counters. Call this first.",
                schema(prop("refresh", "boolean", "Force a fresh read instead of the cached snapshot (cache is a few seconds).")),
                readOnly(), this::getStatus));
        specs.add(tool("luxtronik_search_registers",
                "Search the ~1900 known controller registers by name fragments (python-luxtronik naming: ID_WEB_* = measured "
                        + "calculations, ID_Einst_*/ID_Ba_* = settings/parameters, ID_Visi_* = UI visibility flags). All whitespace "
                        + "separated tokens must match name, type, unit or description. Returns index, name, type, current value "
                        + "and whether the register is writeable. German abbreviations: TVL Vorlauf=flow, TRL Ruecklauf=return, "
                        + "TA Aussen=outdoor, TBW/BW Brauchwarmwasser=hot water, TWE/TWA Waermequelle Ein/Aus=brine in/out, "
                        + "VD Verdichter=compressor, ZWE Zusatzheizung=electric heater, HUP=heating pump, VBO=brine pump, "
                        + "WMZ=heat meter, BZ Betriebszustand=operating state, Hz=heating, Ku/Kuehl=cooling, MK=mixing circuit, "
                        + "Einst=setting, Soll=setpoint, Ist=actual, akt=current.",
                schema(List.of("query"),
                        prop("query", "string", "Space separated name fragments, e.g. 'Temperatur TVL' or 'Heizkurve' or 'BWS'."),
                        enumProp("kind", "Restrict to one register kind.", "all", "parameters", "calculations", "visibilities"),
                        prop("include_unknown", "boolean", "Also list registers whose meaning is not decoded yet (default false)."),
                        prop("writeable_only", "boolean", "Only registers python-luxtronik marks as writeable (default false)."),
                        prop("limit", "integer", "Maximum number of results (default 40, max 200).")),
                readOnly(), this::searchRegisters));
        specs.add(tool("luxtronik_get_registers",
                "Read specific registers by exact name (case-insensitive) or by numeric index together with 'kind'. "
                        + "Returns decoded value with unit, raw value, type and allowed options.",
                schema(List.of("names"),
                        arrayProp("names", "Register names such as ID_WEB_Temperatur_TVL, or indices as strings when 'kind' is given."),
                        enumProp("kind", "Required when addressing registers by numeric index.", "parameters", "calculations", "visibilities"),
                        prop("refresh", "boolean", "Force a fresh read (default: use cached snapshot if younger than the cache TTL).")),
                readOnly(), this::getRegisters));
        specs.add(tool("luxtronik_list_registers",
                "Page through all registers of one kind with their current values (a full dump). Use for systematic exploration; "
                        + "prefer luxtronik_search_registers for targeted questions.",
                schema(List.of("kind"),
                        enumProp("kind", "Register kind to list.", "parameters", "calculations", "visibilities"),
                        prop("offset", "integer", "Start index into the result list (default 0)."),
                        prop("limit", "integer", "Page size (default 100, max 500)."),
                        prop("include_unknown", "boolean", "Include undecoded registers (default false).")),
                readOnly(), this::listRegisters));
        specs.add(tool("luxtronik_get_errors",
                "The controller's error memory (last 5 errors with timestamps) and the switch-off log (last 5 reasons the "
                        + "compressor stopped, e.g. 'no request', 'evu lock', 'high pressure').",
                schema(prop("refresh", "boolean", "Force a fresh read.")),
                readOnly(), this::getErrors));
        specs.add(tool("luxtronik_list_writable",
                "List the parameters this server is allowed to write, with allowed value ranges/options, explanation and current "
                        + "value. Also tells whether writing is enabled at all.",
                schema(), readOnly(), this::listWritable));
        if (pump.policy().enabled()) {
            specs.add(tool("luxtronik_set_parameter",
                    "Change one heat pump setting. Only allowlisted parameters within safe ranges are accepted "
                            + "(see luxtronik_list_writable). Values are given in user units: temperatures in °C/K (e.g. '45.5'), "
                            + "modes by option name (e.g. 'Automatic', 'Off', 'Party'), booleans as true/false. The write is "
                            + "verified by reading the controller back. Writes wear the controller's flash memory: change settings "
                            + "deliberately, not experimentally, and confirm with the user before writing.",
                    schema(List.of("name", "value"),
                            prop("name", "string", "Parameter register name, e.g. ID_Einst_BWS_akt or ID_Ba_Hz_akt."),
                            prop("value", "string", "New value in user units, e.g. '47' or 'Automatic'.")),
                    ToolAnnotations.builder().title("Set heat pump parameter").readOnlyHint(false).destructiveHint(true)
                            .idempotentHint(true).openWorldHint(false).build(),
                    this::setParameter));
        }
        if (docs != null) {
            specs.add(tool("luxtronik_search_docs",
                    "Full-text search in the manufacturer manuals (Luxtronik controller operating manual parts 1+2 and the "
                            + "heat pump manual, German). Returns the best matching passages with document, page and section. "
                            + "Use it for: what a setting means and its allowed range, menu paths on the display, error code causes "
                            + "and remedies, operating limits and technical data. Query in German works best (e.g. 'Heizkurve "
                            + "Parallelverschiebung', 'Hysterese Warmwasser', 'Einsatzgrenzen Wärmequelle', 'Fehler 715').",
                    schema(List.of("query"),
                            prop("query", "string", "Search terms, German preferred; umlauts optional (ue = ü)."),
                            prop("limit", "integer", "Number of passages to return (default 5, max 20).")),
                    readOnly(), this::searchDocs));
            specs.add(tool("luxtronik_get_doc_page",
                    "Full text of one manual page, for reading around a search hit. Documents: " + docNames() + ".",
                    schema(List.of("document", "page"),
                            prop("document", "string", "Document name or unique fragment of it (e.g. 'Teil2')."),
                            prop("page", "integer", "1-based PDF page number as reported by luxtronik_search_docs.")),
                    readOnly(), this::getDocPage));
        }
        if (homeAssistant != null) {
            specs.add(tool("luxtronik_get_history",
                    "Time series of a Home Assistant entity from the HA recorder (e.g. the Luxtronik integration's sensors), "
                            + "downsampled, with min/max/average. Use luxtronik_get_registers for the live value; use this for trends.",
                    schema(List.of("entity_id"),
                            prop("entity_id", "string", "Home Assistant entity id, e.g. sensor.luxtronik_flow_in_temperature."),
                            prop("hours", "number", "How far back to look (default 24, max 24*90)."),
                            prop("max_points", "integer", "Maximum number of samples returned (default 60, max 500).")),
                    readOnly(), this::getHistory));
        }
        return specs;
    }

    // ------------------------------------------------------------------ tools

    private String getStatus(Map<String, Object> args) throws Exception {
        Snapshot s = pump.snapshot(bool(args, "refresh", false));
        return StatusReport.render(s, pump.client().host());
    }

    private String searchRegisters(Map<String, Object> args) throws Exception {
        String query = string(args, "query", "");
        String kindArg = string(args, "kind", "all");
        RegisterKind kind = "all".equalsIgnoreCase(kindArg) ? null : RegisterKind.parse(kindArg);
        int limit = clamp(integer(args, "limit", 40), 1, 200);
        List<RegisterDef> hits = pump.registers().search(query, kind, bool(args, "include_unknown", false),
                bool(args, "writeable_only", false));
        Snapshot s = pump.snapshot(false);
        StringBuilder b = new StringBuilder();
        b.append(hits.size()).append(" register(s) match '").append(query).append("'");
        if (hits.size() > limit) {
            b.append(", showing first ").append(limit).append(" (narrow the query or raise limit)");
        }
        b.append(".\n\n");
        table(b, s, hits.subList(0, Math.min(limit, hits.size())));
        return b.toString();
    }

    private String getRegisters(Map<String, Object> args) throws Exception {
        List<String> names = stringList(args, "names");
        if (names.isEmpty()) {
            throw new IllegalArgumentException("'names' must contain at least one register name");
        }
        String kindArg = string(args, "kind", null);
        RegisterKind kind = kindArg == null ? null : RegisterKind.parse(kindArg);
        Snapshot s = pump.snapshot(bool(args, "refresh", false));
        StringBuilder b = new StringBuilder();
        for (String n : names) {
            Optional<RegisterDef> def = kind != null ? pump.registers().find(kind, n) : pump.registers().find(n);
            if (def.isEmpty()) {
                if (kind == null && n.trim().matches("\\d+")) {
                    b.append("- ").append(n).append(": numeric index needs 'kind' (parameters/calculations/visibilities)\n");
                } else {
                    b.append("- ").append(n).append(": unknown register (try luxtronik_search_registers)\n");
                }
                continue;
            }
            RegisterDef d = def.get();
            b.append("- **").append(d.name()).append("** (").append(d.kind().jsonKey).append("[").append(d.index()).append("], ")
                    .append(d.type().name()).append(d.writeable() ? ", writeable" : "").append("): ")
                    .append(s.format(d));
            Optional<int[]> raws = s.raws(d);
            raws.ifPresent(r -> b.append("  raw=").append(r.length == 1 ? String.valueOf(r[0]) : java.util.Arrays.toString(r)));
            if (!d.type().options().isEmpty()) {
                b.append("  options=").append(d.type().options());
            }
            if (d.names().size() > 1) {
                b.append("  aliases=").append(d.names().subList(1, d.names().size()));
            }
            if (!d.description().isEmpty()) {
                b.append("  // ").append(d.description());
            }
            b.append('\n');
        }
        return b.toString();
    }

    private String listRegisters(Map<String, Object> args) throws Exception {
        RegisterKind kind = RegisterKind.parse(string(args, "kind", null));
        if (kind == null) {
            throw new IllegalArgumentException("'kind' is required");
        }
        int offset = Math.max(0, integer(args, "offset", 0));
        int limit = clamp(integer(args, "limit", 100), 1, 500);
        boolean includeUnknown = bool(args, "include_unknown", false);
        List<RegisterDef> all = new ArrayList<>();
        for (RegisterDef d : pump.registers().all(kind)) {
            if (includeUnknown || !d.isUnknown()) {
                all.add(d);
            }
        }
        Snapshot s = pump.snapshot(false);
        int end = Math.min(all.size(), offset + limit);
        StringBuilder b = new StringBuilder();
        b.append(kind.jsonKey).append(": ").append(all.size()).append(" known registers, controller sent ")
                .append(s.length(kind)).append(". Showing ").append(offset).append("..").append(end).append(".");
        if (end < all.size()) {
            b.append(" More: offset=").append(end).append('.');
        }
        b.append("\n\n");
        table(b, s, all.subList(Math.min(offset, all.size()), end));
        return b.toString();
    }

    private String getErrors(Map<String, Object> args) throws Exception {
        Snapshot s = pump.snapshot(bool(args, "refresh", false));
        StringBuilder b = new StringBuilder();
        b.append("## Error memory (").append(s.format("ID_WEB_AnzahlFehlerInSpeicher")).append(" entries, newest first)\n");
        for (int i = 0; i < 5; i++) {
            Object nr = s.value("ID_WEB_ERROR_Nr" + i);
            Object time = s.value("ID_WEB_ERROR_Time" + i);
            if (time == null && (nr == null || "no error".equals(nr))) {
                continue;
            }
            b.append("- ").append(time == null ? "(no time)" : time).append(": ").append(nr)
                    .append(" (code ").append(rawOf(s, RegisterKind.CALCULATIONS, "ID_WEB_ERROR_Nr" + i)).append(")\n");
        }
        b.append("\n## Switch-off log (why the compressor stopped, newest first)\n");
        for (int i = 0; i < 5; i++) {
            Object nr = s.value("ID_WEB_Switchoff_file_Nr" + i);
            Object time = s.value("ID_WEB_Switchoff_file_Time" + i);
            if (nr == null && time == null) {
                continue;
            }
            b.append("- ").append(time == null ? "(no time)" : time).append(": ").append(nr).append('\n');
        }
        b.append("\nCurrent display status: ").append(s.format("ID_WEB_HauptMenuStatus_Zeile1")).append(" / ")
                .append(s.format("ID_WEB_HauptMenuStatus_Zeile3")).append('\n');
        if (docs != null) {
            docs.await();
            java.util.Set<Integer> seen = new java.util.TreeSet<>();
            for (int i = 0; i < 5; i++) {
                s.registers().find(RegisterKind.CALCULATIONS, "ID_WEB_ERROR_Nr" + i).flatMap(s::raws)
                        .ifPresent(r -> seen.add(r[0]));
            }
            seen.remove(0);
            StringBuilder m = new StringBuilder();
            for (int code : seen) {
                String info = docs.errorInfo(code);
                if (info != null) {
                    m.append("- **").append(code).append("**: ").append(info).append('\n');
                }
            }
            if (m.length() > 0) {
                b.append("\n## From the manual (Fehlerdiagnose / Fehlermeldungen)\n").append(m);
            }
        }
        return b.toString();
    }

    private String docNames() {
        if (docs == null) {
            return "";
        }
        try {
            docs.await();
        } catch (Exception e) {
            return "(index not loaded: " + e.getMessage() + ")";
        }
        StringBuilder b = new StringBuilder();
        for (String d : docs.documents()) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(d).append(" (").append(docs.pageCount(d)).append(" pages)");
        }
        return b.toString();
    }

    private String searchDocs(Map<String, Object> args) throws Exception {
        String query = string(args, "query", "");
        if (query.isBlank()) {
            throw new IllegalArgumentException("'query' is required");
        }
        int limit = clamp(integer(args, "limit", 5), 1, 20);
        docs.await();
        List<DocIndex.Hit> hits = docs.search(query, limit);
        if (hits.isEmpty()) {
            return "No passage matches '" + query + "'. Try German terms, fewer words, or a menu name (Heizkurve, Trinkwarmwasser, Kühlung, Systemeinstellung).";
        }
        StringBuilder b = new StringBuilder();
        b.append(hits.size()).append(" passage(s) for '").append(query).append("' (use luxtronik_get_doc_page for the whole page):\n");
        for (DocIndex.Hit h : hits) {
            b.append("\n### ").append(h.chunk().label()).append('\n').append(h.chunk().text()).append('\n');
        }
        return b.toString();
    }

    private String getDocPage(Map<String, Object> args) throws Exception {
        String document = string(args, "document", null);
        int page = integer(args, "page", 0);
        if (document == null || page < 1) {
            throw new IllegalArgumentException("'document' and a 1-based 'page' are required");
        }
        docs.await();
        String doc = docs.resolveDoc(document);
        if (doc == null) {
            throw new IllegalArgumentException("no unique document matches '" + document + "'. Available: " + docNames());
        }
        String text = docs.page(doc, page);
        if (text == null) {
            throw new IllegalArgumentException(doc + " has " + docs.pageCount(doc) + " pages");
        }
        return "### " + doc + " p." + page + "\n" + text;
    }

    private String listWritable(Map<String, Object> args) throws Exception {
        WritePolicy policy = pump.policy();
        Snapshot s = pump.snapshot(false);
        StringBuilder b = new StringBuilder();
        b.append(policy.enabled()
                ? "Writing is ENABLED. Use luxtronik_set_parameter with one of these names.\n\n"
                : "Writing is DISABLED on this server (start with --allow-write to enable). The allowlist would be:\n\n");
        b.append("| parameter | current | allowed | meaning |\n|---|---|---|---|\n");
        for (WritePolicy.Rule r : policy.rules()) {
            Optional<RegisterDef> def = pump.registers().find(RegisterKind.PARAMETERS, r.name());
            String current = def.map(s::format).orElse("unknown register");
            String allowed;
            if (def.isPresent() && !def.get().type().options().isEmpty()) {
                allowed = String.join(" / ", def.get().type().options());
            } else if (r.min() != null || r.max() != null) {
                allowed = fmt(r.min()) + " .. " + fmt(r.max()) + (def.map(d -> d.type().unit()).orElse(null) != null
                        ? " " + def.get().type().unit() : "");
            } else if (def.isPresent() && def.get().type().kind() == luxmcp.lux.DataType.Kind.BOOL) {
                allowed = "true / false";
            } else {
                allowed = "any";
            }
            b.append("| ").append(r.name()).append(" | ").append(current).append(" | ").append(allowed).append(" | ")
                    .append(r.note() == null ? "" : r.note()).append(" |\n");
        }
        return b.toString();
    }

    private String setParameter(Map<String, Object> args) throws Exception {
        String name = string(args, "name", null);
        String value = string(args, "value", null);
        if (name == null || value == null) {
            throw new IllegalArgumentException("'name' and 'value' are required");
        }
        HeatPump.WriteResult r = pump.write(name, value);
        StringBuilder b = new StringBuilder();
        b.append(r.verified() ? "OK: " : "WARNING: ").append(r.def().name()).append(" (parameters[").append(r.def().index())
                .append("]) was ").append(r.before()).append(", requested ").append(r.requested())
                .append(", controller now reports ").append(r.readBack()).append('.');
        if (!r.verified()) {
            b.append(" The read-back does not match the requested value; the controller may have rejected or clamped it.");
        }
        return b.toString();
    }

    private String getHistory(Map<String, Object> args) throws Exception {
        String entity = string(args, "entity_id", null);
        if (entity == null) {
            throw new IllegalArgumentException("'entity_id' is required");
        }
        double hours = Math.min(24 * 90, Math.max(0.1, number(args, "hours", 24)));
        int maxPoints = clamp(integer(args, "max_points", 60), 2, 500);
        return homeAssistant.history(entity, hours, maxPoints);
    }

    // ------------------------------------------------------------------ helpers

    private static void table(StringBuilder b, Snapshot s, List<RegisterDef> defs) {
        if (defs.isEmpty()) {
            b.append("(none)\n");
            return;
        }
        b.append("| kind | idx | name | type | value | w |\n|---|---|---|---|---|---|\n");
        for (RegisterDef d : defs) {
            b.append("| ").append(d.kind().jsonKey.charAt(0)).append(" | ").append(d.index()).append(" | ").append(d.name())
                    .append(" | ").append(d.type().name()).append(" | ").append(s.format(d)).append(" | ")
                    .append(d.writeable() ? "W" : "").append(" |\n");
        }
    }

    private static String rawOf(Snapshot s, RegisterKind kind, String name) {
        return s.registers().find(kind, name).flatMap(s::raws).map(r -> String.valueOf(r[0])).orElse("?");
    }

    private static String fmt(Double d) {
        if (d == null) {
            return "";
        }
        return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean bool(Map<String, Object> args, String key, boolean def) {
        Object v = args.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static int integer(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return Integer.parseInt(String.valueOf(v).trim());
    }

    private static double number(Map<String, Object> args, String key, double def) {
        Object v = args.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return Double.parseDouble(String.valueOf(v).trim());
    }

    private static String string(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v == null ? def : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<Object>) v) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (v != null) {
            for (String s : String.valueOf(v).split("[,\\s]+")) {
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static ToolAnnotations readOnly() {
        return ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false).idempotentHint(true).openWorldHint(false).build();
    }

    private static Map<String, Object> prop(String name, String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(name, p);
        return m;
    }

    private static Map<String, Object> enumProp(String name, String description, String... values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        p.put("enum", List.of(values));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(name, p);
        return m;
    }

    private static Map<String, Object> arrayProp(String name, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("items", Map.of("type", "string"));
        p.put("description", description);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(name, p);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> schema(Map<String, Object>... props) {
        return schema(List.of(), props);
    }

    @SafeVarargs
    private static Map<String, Object> schema(List<String> required, Map<String, Object>... props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map<String, Object> p : props) {
            properties.putAll(p);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (!required.isEmpty()) {
            s.put("required", required);
        }
        s.put("additionalProperties", false);
        return s;
    }

    private interface Handler {
        String run(Map<String, Object> args) throws Exception;
    }

    private static SyncToolSpecification tool(String name, String description, Map<String, Object> inputSchema,
                                              ToolAnnotations annotations, Handler handler) {
        Tool tool = Tool.builder(name, inputSchema).description(description).annotations(annotations).build();
        return SyncToolSpecification.builder().tool(tool).callHandler((McpSyncServerExchange exchange, CallToolRequest req) -> {
            Map<String, Object> args = req.arguments() == null ? Map.of() : req.arguments();
            try {
                String text = handler.run(args);
                return CallToolResult.builder().addTextContent(text).isError(false).build();
            } catch (WritePolicy.PolicyException e) {
                log.warn("{} refused: {}", name, e.getMessage());
                return error("Refused: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                return error("Invalid input: " + e.getMessage());
            } catch (java.io.IOException e) {
                log.warn("{} failed: {}", name, e.toString());
                return error("Heat pump communication failed: " + e.getMessage()
                        + ". Check that the controller is reachable and not busy (only one client should talk to it at a time).");
            } catch (Exception e) {
                log.error("{} crashed", name, e);
                return error("Internal error in " + name + ": " + e);
            }
        }).build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    /** Kept for symmetry with structured output should it be added later. */
    @SuppressWarnings("unused")
    private static McpSchema.Content text(String s) {
        return McpSchema.TextContent.builder(s).build();
    }

    @SuppressWarnings("unused")
    private static <T> Function<Map<String, Object>, T> constant(T t) {
        return m -> t;
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
