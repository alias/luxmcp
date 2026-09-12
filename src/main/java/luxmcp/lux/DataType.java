package luxmcp.lux;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Conversion between raw controller register values and user-facing values.
 * <p>
 * Mirrors the datatype classes of python-luxtronik ({@code luxtronik/datatypes.py}); the concrete
 * instances are loaded from {@code registers.json}, which is generated from that module.
 */
public final class DataType {

    public enum Kind {
        RAW, SCALING, SELECTION, BITMASK, BOOL, TIMESTAMP, IPV4, VERSION, FULLVERSION, CHARACTER,
        MAJORMINOR, HOURS2, TIMEOFDAY, TIMEOFDAY2
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String name;
    private final Kind kind;
    private final String dtClass;
    private final String unit;
    private final boolean concatenate;
    // scaling
    private final double factor;
    private final int width;
    private final boolean signed;
    private final int decimals;
    // selection / bitmask
    private final Map<Integer, String> codes;
    private final String zero;
    private final String delim;
    private final String postfix;

    private DataType(String name, Kind kind, String dtClass, String unit, boolean concatenate, double factor,
                     int width, boolean signed, Map<Integer, String> codes, String zero, String delim, String postfix) {
        this.name = name;
        this.kind = kind;
        this.dtClass = dtClass;
        this.unit = unit;
        this.concatenate = concatenate;
        this.factor = factor;
        this.width = width;
        this.signed = signed;
        this.decimals = countDecimals(factor);
        this.codes = codes == null ? Collections.emptyMap() : Collections.unmodifiableMap(new TreeMap<>(codes));
        this.zero = zero;
        this.delim = delim;
        this.postfix = postfix;
    }

    /** Simple constructor for tests / ad-hoc types. */
    public static DataType scaling(String name, String unit, double factor) {
        return new DataType(name, Kind.SCALING, null, unit, true, factor, 32, true, null, null, null, null);
    }

    public static DataType raw(String name, String unit) {
        return new DataType(name, Kind.RAW, null, unit, true, 1, 32, true, null, null, null, null);
    }

    public static DataType selection(String name, Map<Integer, String> codes) {
        return new DataType(name, Kind.SELECTION, "selection", null, true, 1, 32, true, codes, null, null, null);
    }

    public static DataType simple(String name, Kind kind) {
        return new DataType(name, kind, null, null, kind != Kind.VERSION && kind != Kind.FULLVERSION, 1, 32, true, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    static DataType fromJson(String name, Map<String, Object> m) {
        Kind kind = Kind.valueOf(str(m.get("kind")).toUpperCase(Locale.ROOT));
        Map<Integer, String> codes = null;
        Object codeMap = kind == Kind.SELECTION ? m.get("codes") : kind == Kind.BITMASK ? m.get("bits") : null;
        if (codeMap instanceof Map) {
            codes = new TreeMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) codeMap).entrySet()) {
                codes.put(Integer.parseInt(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return new DataType(name, kind, nullable(m.get("class")), nullable(m.get("unit")),
                m.get("concatenate") == null || Boolean.TRUE.equals(m.get("concatenate")),
                m.get("factor") == null ? 1 : ((Number) m.get("factor")).doubleValue(),
                m.get("width") == null ? 32 : ((Number) m.get("width")).intValue(),
                m.get("signed") == null || Boolean.TRUE.equals(m.get("signed")),
                codes, nullable(m.get("zero")), nullable(m.get("delim")), nullable(m.get("postfix")));
    }

    private static String nullable(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** Unit symbol of the *decoded* value ({@code °C}, {@code bar}, {@code h}, ...), or null. */
    public String unit() {
        return unit;
    }

    /** Semantic class from python-luxtronik ("temperature", "selection", ...), may be null. */
    public String dtClass() {
        return dtClass;
    }

    public boolean concatenate() {
        return concatenate;
    }

    /** Allowed textual values for selection types, in code order. Empty for other kinds. */
    public List<String> options() {
        return new ArrayList<>(codes.values());
    }

    public Map<Integer, String> codes() {
        return codes;
    }

    // ------------------------------------------------------------------ decode

    /**
     * Decode raw register value(s) into a user value: Double for scaled numbers, Long for raw counters,
     * Boolean, or String (selection names, timestamps, versions ...). Returns null if {@code raws} is null.
     */
    public Object decode(int[] raws) {
        if (raws == null || raws.length == 0) {
            return null;
        }
        switch (kind) {
            case VERSION: {
                StringBuilder sb = new StringBuilder();
                for (int c : raws) {
                    if (c != 0) {
                        sb.append((char) c);
                    }
                }
                return sb.toString().trim();
            }
            case FULLVERSION:
                if (raws.length < 3) {
                    return null;
                }
                return raws[0] + "." + raws[1] + "." + raws[2];
            default:
                break;
        }
        long raw = raws.length == 1 ? raws[0] : pack(raws);
        switch (kind) {
            case RAW:
                return raw;
            case SCALING: {
                long v = raw;
                if (width < 32) {
                    v &= (1L << width) - 1;
                    if (signed && v > (1L << (width - 1)) - 1) {
                        v -= 1L << width;
                    }
                } else if (!signed) {
                    v &= 0xFFFFFFFFL;
                }
                return BigDecimal.valueOf(v).multiply(BigDecimal.valueOf(factor))
                        .setScale(decimals, RoundingMode.HALF_UP).doubleValue();
            }
            case SELECTION: {
                String s = codes.get((int) raw);
                return s != null ? s : "Unknown_" + raw;
            }
            case BITMASK: {
                if (raw == 0) {
                    return zero;
                }
                List<String> parts = new ArrayList<>();
                for (int bit = 0; bit < 32; bit++) {
                    if ((raw & (1L << bit)) != 0) {
                        String s = codes.get(bit);
                        parts.add(s != null ? s : "Unknown_" + bit);
                    }
                }
                return String.join(delim == null ? ", " : delim, parts) + (postfix == null ? "" : postfix);
            }
            case BOOL:
                return raw != 0;
            case TIMESTAMP:
                if (raw <= 0) {
                    return null;
                }
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(raw), ZoneId.systemDefault()).format(TS);
            case IPV4:
                return ((raw >> 24) & 255) + "." + ((raw >> 16) & 255) + "." + ((raw >> 8) & 255) + "." + (raw & 255);
            case CHARACTER:
                return raw == 0 ? "" : String.valueOf((char) raw);
            case MAJORMINOR:
                return raw > 0 ? (raw / 100) + "." + (raw % 100) : "0";
            case HOURS2:
                return 1 + raw / 2.0;
            case TIMEOFDAY: {
                long h = raw / 3600, m = (raw / 60) % 60, s = raw % 60;
                return String.format("%d:%02d", h, m) + (s > 0 ? String.format(":%02d", s) : "");
            }
            case TIMEOFDAY2: {
                long lo = raw & 0xFFFF, hi = (raw >> 16) & 0xFFFF;
                return String.format("%d:%02d-%d:%02d", lo / 60, lo % 60, hi / 60, hi % 60);
            }
            default:
                return raw;
        }
    }

    /** Human readable form of {@link #decode(int[])} including the unit. */
    public String format(int[] raws) {
        Object v = decode(raws);
        return format(v);
    }

    public String format(Object v) {
        if (v == null) {
            return "n/a";
        }
        String s;
        if (v instanceof Double) {
            s = kind == Kind.SCALING ? BigDecimal.valueOf((Double) v).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
                    : stripZeros((Double) v);
        } else {
            s = String.valueOf(v);
        }
        return unit != null && !unit.isEmpty() ? s + " " + unit : s;
    }

    private static String stripZeros(double d) {
        if (d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    // ------------------------------------------------------------------ encode

    /**
     * Convert a user supplied value (as typed by the model/user) into the raw register value.
     *
     * @throws IllegalArgumentException with a helpful message if the value cannot be converted
     */
    public int encode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        String v = value.trim();
        switch (kind) {
            case SCALING: {
                double d = parseDouble(v);
                long raw = Math.round(d / factor);
                if (raw > Integer.MAX_VALUE || raw < Integer.MIN_VALUE) {
                    throw new IllegalArgumentException("value out of range: " + value);
                }
                return (int) raw;
            }
            case SELECTION: {
                String want = sanitize(v);
                for (Map.Entry<Integer, String> e : codes.entrySet()) {
                    if (sanitize(e.getValue()).equals(want)) {
                        return e.getKey();
                    }
                }
                if (want.startsWith("unknown_")) {
                    return Integer.parseInt(want.substring("unknown_".length()));
                }
                try {
                    int code = Integer.parseInt(v);
                    if (codes.containsKey(code)) {
                        return code;
                    }
                } catch (NumberFormatException ignored) {
                    // fall through
                }
                throw new IllegalArgumentException("'" + value + "' is not a valid option. Allowed: " + options());
            }
            case BOOL: {
                String w = v.toLowerCase(Locale.ROOT);
                if (w.equals("true") || w.equals("1") || w.equals("on") || w.equals("yes") || w.equals("ein")) {
                    return 1;
                }
                if (w.equals("false") || w.equals("0") || w.equals("off") || w.equals("no") || w.equals("aus")) {
                    return 0;
                }
                throw new IllegalArgumentException("'" + value + "' is not a boolean (use true/false)");
            }
            case RAW:
                return (int) Long.parseLong(v);
            case HOURS2:
                return (int) Math.round((parseDouble(v) - 1) * 2);
            case TIMEOFDAY: {
                String[] p = v.split(":");
                if (p.length < 2 || p.length > 3) {
                    throw new IllegalArgumentException("time of day must be H:MM or H:MM:SS, got '" + value + "'");
                }
                int s = Integer.parseInt(p[0].trim()) * 3600 + Integer.parseInt(p[1].trim()) * 60;
                if (p.length == 3) {
                    s += Integer.parseInt(p[2].trim());
                }
                return s;
            }
            case TIMEOFDAY2: {
                String[] range = v.split("-");
                if (range.length != 2) {
                    throw new IllegalArgumentException("time range must be H:MM-H:MM, got '" + value + "'");
                }
                String[] lo = range[0].trim().split(":");
                String[] hi = range[1].trim().split(":");
                int low = Integer.parseInt(lo[0]) * 60 + Integer.parseInt(lo[1]);
                int high = Integer.parseInt(hi[0]) * 60 + Integer.parseInt(hi[1]);
                return (high << 16) + low;
            }
            case TIMESTAMP: {
                try {
                    return (int) Long.parseLong(v);
                } catch (NumberFormatException e) {
                    return (int) LocalDateTime.parse(v.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toEpochSecond();
                }
            }
            default:
                throw new IllegalArgumentException("writing values of type " + name + " is not supported");
        }
    }

    private static double parseDouble(String v) {
        String s = v.replace(',', '.');
        int cut = s.indexOf(' ');
        if (cut > 0) {
            s = s.substring(0, cut); // tolerate "45.5 °C"
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + v + "' is not a number");
        }
    }

    private static String sanitize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('-', '_').trim();
    }

    static long pack(int[] raws) {
        long result = 0;
        for (int r : raws) {
            result = (result << 32) | (r & 0xFFFFFFFFL);
        }
        return result;
    }

    private static int countDecimals(double factor) {
        String s = BigDecimal.valueOf(factor).stripTrailingZeros().toPlainString();
        int dot = s.indexOf('.');
        return dot < 0 ? 0 : s.length() - dot - 1;
    }

    /** Short JSON-ish description used in tool output. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", name);
        m.put("kind", kind.name().toLowerCase(Locale.ROOT));
        if (unit != null) {
            m.put("unit", unit);
        }
        if (kind == Kind.SELECTION) {
            m.put("options", options());
        }
        if (kind == Kind.SCALING) {
            m.put("resolution", factor);
        }
        return m;
    }

    @Override
    public String toString() {
        return name;
    }
}
