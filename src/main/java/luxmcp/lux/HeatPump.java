package luxmcp.lux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Facade over one heat pump: cached snapshots plus policy-checked, audited writes.
 */
public final class HeatPump {

    private static final Logger log = LoggerFactory.getLogger(HeatPump.class);
    private static final Duration MIN_WRITE_INTERVAL = Duration.ofSeconds(5);
    private static final long SETTLE_AFTER_WRITE_MS = 1_000;

    public record WriteResult(RegisterDef def, String before, String requested, int rawRequested, String readBack,
                              int rawReadBack) {
        public boolean verified() {
            return rawReadBack == rawRequested;
        }
    }

    private final LuxtronikClient client;
    private final Registers registers;
    private final Duration cacheTtl;
    private final WritePolicy policy;
    private final Path auditLog;

    private Snapshot cached;
    private Instant lastWrite = Instant.EPOCH;

    public HeatPump(LuxtronikClient client, Registers registers, Duration cacheTtl, WritePolicy policy, Path auditLog) {
        this.client = client;
        this.registers = registers;
        this.cacheTtl = cacheTtl;
        this.policy = policy;
        this.auditLog = auditLog;
    }

    public LuxtronikClient client() {
        return client;
    }

    public Registers registers() {
        return registers;
    }

    public WritePolicy policy() {
        return policy;
    }

    /** Current snapshot; served from cache unless older than the TTL or {@code refresh} is set. */
    public synchronized Snapshot snapshot(boolean refresh) throws IOException {
        if (!refresh && cached != null && cached.readAt().plus(cacheTtl).isAfter(Instant.now())) {
            return cached;
        }
        cached = new Snapshot(registers, client.readAll());
        return cached;
    }

    public Optional<Snapshot> cachedSnapshot() {
        return Optional.ofNullable(cached);
    }

    /**
     * Write one parameter after policy validation, then re-read the controller to verify.
     */
    public synchronized WriteResult write(String name, String value) throws IOException, WritePolicy.PolicyException {
        RegisterDef def = registers.find(RegisterKind.PARAMETERS, name)
                .orElseThrow(() -> new WritePolicy.PolicyException("unknown parameter '" + name
                        + "'. Use luxtronik_search_registers to find the exact register name."));
        int raw = policy.check(def, value);

        Duration sinceLast = Duration.between(lastWrite, Instant.now());
        if (sinceLast.compareTo(MIN_WRITE_INTERVAL) < 0) {
            throw new WritePolicy.PolicyException("last write was " + sinceLast.toMillis() + " ms ago; wait at least "
                    + MIN_WRITE_INTERVAL.toSeconds() + " s between writes (controller flash wear).");
        }

        Snapshot before = snapshot(true);
        String beforeText = before.format(def);
        int rawBefore = before.raws(def).map(r -> r[0]).orElse(Integer.MIN_VALUE);
        if (rawBefore == raw) {
            audit(def, beforeText, value, raw, "unchanged (already set)");
            return new WriteResult(def, beforeText, value, raw, beforeText, raw);
        }

        lastWrite = Instant.now();
        client.writeParameter(def.index(), raw);
        try {
            Thread.sleep(SETTLE_AFTER_WRITE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Snapshot after = snapshot(true);
        String afterText = after.format(def);
        int rawAfter = after.raws(def).map(r -> r[0]).orElse(Integer.MIN_VALUE);
        audit(def, beforeText, value, raw, "read back " + afterText + (rawAfter == raw ? "" : " (MISMATCH raw " + rawAfter + ")"));
        return new WriteResult(def, beforeText, value, raw, afterText, rawAfter);
    }

    private void audit(RegisterDef def, String before, String requested, int raw, String outcome) {
        String line = String.format(Locale.ROOT, "%s %s:%d write %s[%d] %s -> %s (raw %d): %s",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), client.host(), client.port(),
                def.name(), def.index(), before, requested, raw, outcome);
        log.info(line);
        if (auditLog != null) {
            try {
                Files.writeString(auditLog, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("cannot append to audit log {}: {}", auditLog, e.toString());
            }
        }
    }
}
