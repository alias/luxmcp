package luxmcp.lux;

import java.util.List;

/**
 * Metadata of one register (or a run of {@code count} consecutive registers) of the Luxtronik CFI.
 * Loaded from {@code registers.json}, which is generated from python-luxtronik's definition tables.
 */
public record RegisterDef(RegisterKind kind, int index, int count, List<String> names, DataType type,
                          boolean writeable, String datatype, String unit, String description,
                          String since, String until, String successor) {

    private static final int NOT_AVAILABLE_32 = 0x7FFFFFFF;
    private static final int NOT_AVAILABLE_16 = 0x7FFF;

    /** Preferred (first) name. */
    public String name() {
        return names.get(0);
    }

    /** True for registers whose meaning the community has not decoded yet. */
    public boolean isUnknown() {
        return "Unknown".equals(type.name()) || name().toLowerCase().startsWith("unknown_");
    }

    /**
     * Since firmware 3.92.0 the controller reports a magic value for functions that do not exist on
     * the installed hardware. Mirrors {@code LuxtronikDefinition.check_raw_not_none}.
     */
    public boolean isNotAvailable(int raw) {
        if (count != 1) {
            return false;
        }
        if ("INT16".equals(datatype) || "UINT16".equals(datatype)) {
            return raw == NOT_AVAILABLE_16;
        }
        if ("INT32".equals(datatype) || "UINT32".equals(datatype)) {
            return raw == NOT_AVAILABLE_32;
        }
        return false;
    }

    public boolean matchesName(String n) {
        for (String s : names) {
            if (s.equalsIgnoreCase(n)) {
                return true;
            }
        }
        return false;
    }
}
