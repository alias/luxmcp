package luxmcp.lux;

/**
 * The three data vectors the Luxtronik config interface (CFI, TCP port 8889) exposes.
 */
public enum RegisterKind {
    /** Settings. Persistent, readable and (partly) writeable. Names mostly {@code ID_Einst_*}, {@code ID_Ba_*}. */
    PARAMETERS("parameters", 3003),
    /** Measured / computed values. Read-only. Names mostly {@code ID_WEB_*}. */
    CALCULATIONS("calculations", 3004),
    /** UI visibility flags (which menu entries the controller shows). Read-only, one byte each. Names {@code ID_Visi_*}. */
    VISIBILITIES("visibilities", 3005);

    public final String jsonKey;
    public final int readCommand;

    RegisterKind(String jsonKey, int readCommand) {
        this.jsonKey = jsonKey;
        this.readCommand = readCommand;
    }

    public static RegisterKind parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim().toLowerCase();
        for (RegisterKind k : values()) {
            if (k.jsonKey.equals(v) || k.name().toLowerCase().equals(v)) {
                return k;
            }
        }
        switch (v) {
            case "param": case "params": case "parameter": return PARAMETERS;
            case "calc": case "calcs": case "calculation": return CALCULATIONS;
            case "vis": case "visibility": return VISIBILITIES;
            default: throw new IllegalArgumentException("Unknown register kind '" + s + "'. Use parameters, calculations or visibilities.");
        }
    }
}
