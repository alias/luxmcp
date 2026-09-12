package luxmcp.lux;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * One decoded point-in-time view of the controller: the raw vectors plus the dictionary needed to
 * interpret them.
 */
public final class Snapshot {

    private final Registers registers;
    private final LuxtronikClient.RawData raw;

    public Snapshot(Registers registers, LuxtronikClient.RawData raw) {
        this.registers = registers;
        this.raw = raw;
    }

    public Registers registers() {
        return registers;
    }

    public Instant readAt() {
        return raw.readAt();
    }

    public int length(RegisterKind kind) {
        return raw.get(kind).length;
    }

    /** Raw register(s) backing a definition, or empty if the controller sent fewer registers. */
    public Optional<int[]> raws(RegisterDef d) {
        int[] vec = raw.get(d.kind());
        int end = d.index() + d.count();
        if (d.index() < 0 || end > vec.length) {
            return Optional.empty();
        }
        return Optional.of(Arrays.copyOfRange(vec, d.index(), end));
    }

    public int rawValue(RegisterKind kind, int index) {
        int[] vec = raw.get(kind);
        if (index < 0 || index >= vec.length) {
            throw new IndexOutOfBoundsException("register " + kind.jsonKey + "[" + index + "] not sent by controller");
        }
        return vec[index];
    }

    /** Decoded value or null when missing / reported as "not available". */
    public Object value(RegisterDef d) {
        Optional<int[]> r = raws(d);
        if (r.isEmpty()) {
            return null;
        }
        int[] rr = r.get();
        if (rr.length == 1 && d.isNotAvailable(rr[0])) {
            return null;
        }
        return d.type().decode(rr);
    }

    /** Decoded value looked up by name across all kinds; null if unknown or missing. */
    public Object value(String name) {
        return registers.find(name).map(this::value).orElse(null);
    }

    public Object value(RegisterKind kind, String nameOrIndex) {
        return registers.find(kind, nameOrIndex).map(this::value).orElse(null);
    }

    /** Formatted value with unit, "n/a" if missing. */
    public String format(RegisterDef d) {
        return d.type().format(value(d));
    }

    public String format(String name) {
        return registers.find(name).map(this::format).orElse("n/a");
    }

    /** Numeric value as double, or null. */
    public Double number(String name) {
        Object v = value(name);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    /** Firmware version string like {@code V3.92.3} (calculations 81..90). */
    public String firmware() {
        Object v = value(RegisterKind.CALCULATIONS, "ID_WEB_SoftStand");
        return v == null ? "unknown" : String.valueOf(v);
    }
}
