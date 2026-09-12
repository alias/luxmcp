package luxmcp.lux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which parameters may be written and with which values.
 * <p>
 * Writing to the Luxtronik CFI is an undocumented, reverse-engineered API and the controller keeps
 * its configuration in NAND flash with a limited number of erase cycles. This policy therefore
 * (1) requires writes to be switched on explicitly, (2) only allows an explicit allowlist of
 * everyday settings, (3) additionally requires the upstream definition to be marked writeable, and
 * (4) enforces value ranges in user units.
 */
public final class WritePolicy {

    /** One allowlist entry. {@code min}/{@code max} are in decoded user units and may be null. */
    public record Rule(String name, Double min, Double max, String note) {
    }

    public static final class PolicyException extends Exception {
        public PolicyException(String message) {
            super(message);
        }
    }

    /** Everyday settings a home owner changes from the display anyway. */
    public static final List<Rule> DEFAULT_RULES = List.of(
            new Rule("ID_Ba_Hz_akt", null, null, "Heating operating mode"),
            new Rule("ID_Ba_Bw_akt", null, null, "Hot water (DHW) operating mode"),
            new Rule("ID_Einst_BA_Kuehl_akt", null, null, "Cooling operating mode"),
            new Rule("ID_Einst_WK_akt", -5.0, 5.0, "Heating 'Temperatur +/-': return temperature offset in K applied on top of the heating curve"),
            new Rule("ID_Einst_BWS_akt", 30.0, 60.0, "Hot water target temperature in °C"),
            new Rule("ID_Einst_BWS_Hyst_akt", 1.0, 15.0, "Hot water hysteresis in K (reheat starts target minus hysteresis)"),
            new Rule("ID_Einst_HzHwHKE_akt", 20.0, 70.0, "Heating curve end point: return setpoint at -20 °C outdoor, °C"),
            new Rule("ID_Einst_HzHKRANH_akt", 15.0, 35.0, "Heating curve parallel shift: return setpoint at +20 °C outdoor, °C"),
            new Rule("ID_Einst_HzHKRABS_akt", -15.0, 0.0, "Heating curve night setback (Absenkung) in K, 0 = none"),
            new Rule("ID_Einst_HzMK1E_akt", 20.0, 70.0, "Mixing circuit 1 curve end point, °C"),
            new Rule("ID_Einst_HzMK1ANH_akt", 15.0, 35.0, "Mixing circuit 1 curve parallel shift, °C"),
            new Rule("ID_Einst_HzMK1ABS_akt", -15.0, 0.0, "Mixing circuit 1 night setback in K"),
            new Rule("ID_Einst_KuehlFreig_akt", 15.0, 35.0, "Outdoor temperature above which cooling is released, °C"),
            new Rule("ID_Sollwert_KuCft1_akt", 15.0, 30.0, "Cooling flow setpoint mixing circuit 1, °C"),
            new Rule("ID_Sollwert_AtDif1_akt", 1.0, 15.0, "Cooling: outdoor temperature difference for mixing circuit 1, K"),
            new Rule("ID_Einst_Minimale_Ruecklaufsolltemperatur", 15.0, 30.0, "Minimum return setpoint temperature, °C"),
            new Rule("ID_Einst_Warmwasser_Nachheizung", null, null, "Allow electric reheating (ZWE) of hot water"),
            new Rule("ID_Einst_WW_Nachheizung_max", 1.0, 5.0, "Maximum duration of electric hot water reheating, h")
    );

    private final boolean enabled;
    private final Map<String, Rule> rules;

    private WritePolicy(boolean enabled, List<Rule> rules) {
        this.enabled = enabled;
        Map<String, Rule> m = new LinkedHashMap<>();
        for (Rule r : rules) {
            m.put(r.name().toLowerCase(Locale.ROOT), r);
        }
        this.rules = Collections.unmodifiableMap(m);
    }

    public static WritePolicy disabled() {
        return new WritePolicy(false, DEFAULT_RULES);
    }

    public static WritePolicy defaults() {
        return new WritePolicy(true, DEFAULT_RULES);
    }

    public static WritePolicy of(List<Rule> rules) {
        return new WritePolicy(true, rules);
    }

    /**
     * Load an allowlist file. One rule per line: {@code NAME [min max] [# note]}. Blank lines and
     * lines starting with {@code #} are ignored. Replaces the default list entirely.
     */
    public static WritePolicy fromFile(Path file) throws IOException {
        List<Rule> rules = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String note = null;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                note = line.substring(hash + 1).trim();
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split("\\s+");
            Double min = null, max = null;
            if (p.length >= 3) {
                min = Double.parseDouble(p[1]);
                max = Double.parseDouble(p[2]);
            } else if (p.length == 2) {
                throw new IOException("allowlist line needs NAME or NAME min max: " + line);
            }
            rules.add(new Rule(p[0], min, max, note));
        }
        return new WritePolicy(true, rules);
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Rule> rules() {
        return new ArrayList<>(rules.values());
    }

    public Rule rule(RegisterDef def) {
        for (String n : def.names()) {
            Rule r = rules.get(n.toLowerCase(Locale.ROOT));
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /**
     * Validate a write and return the raw register value to send.
     *
     * @throws PolicyException if the write is not permitted
     * @throws IllegalArgumentException if the value cannot be converted for this datatype
     */
    public int check(RegisterDef def, String value) throws PolicyException {
        if (!enabled) {
            throw new PolicyException("Writing is disabled on this server. Start luxmcp with --allow-write to enable it.");
        }
        if (def.kind() != RegisterKind.PARAMETERS) {
            throw new PolicyException(def.name() + " is a " + def.kind().jsonKey + " register; only parameters can be written.");
        }
        Rule rule = rule(def);
        if (rule == null) {
            throw new PolicyException(def.name() + " is not on the write allowlist. Use luxtronik_list_writable to see what may be changed"
                    + " (the allowlist can be extended with --allowlist <file>).");
        }
        if (!def.writeable()) {
            throw new PolicyException(def.name() + " is not marked writeable in the python-luxtronik definitions, refusing to write.");
        }
        int raw = def.type().encode(value);
        if (rule.min() != null || rule.max() != null) {
            Object decoded = def.type().decode(new int[]{raw});
            if (!(decoded instanceof Number)) {
                throw new PolicyException("cannot range-check non-numeric value for " + def.name());
            }
            double d = ((Number) decoded).doubleValue();
            if (rule.min() != null && d < rule.min() || rule.max() != null && d > rule.max()) {
                throw new PolicyException(String.format(Locale.ROOT, "%s = %s is outside the allowed range %s..%s %s",
                        def.name(), def.type().format(decoded), fmt(rule.min()), fmt(rule.max()),
                        def.type().unit() == null ? "" : def.type().unit()).trim());
            }
        }
        return raw;
    }

    private static String fmt(Double d) {
        if (d == null) {
            return "";
        }
        return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }
}
