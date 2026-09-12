package luxmcp.mcp;

import luxmcp.lux.RegisterDef;
import luxmcp.lux.RegisterKind;
import luxmcp.lux.Snapshot;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Curated, human readable overview of the heat pump: what an engineer looks at first.
 */
final class StatusReport {

    private StatusReport() {
    }

    static String render(Snapshot s, String host) {
        StringBuilder b = new StringBuilder();
        String controllerClock = s.format("ID_WEB_AktuelleTimeStamp");
        b.append("# Heat pump status (").append(host).append(")\n");
        b.append("- Controller: ").append(s.format("ID_WEB_Code_WP_akt")).append(", firmware ").append(s.firmware())
                .append(", controller clock ").append(controllerClock).append("\n");
        b.append("- Read at: ").append(LocalDateTime.ofInstant(s.readAt(), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");

        b.append("## Operation\n");
        line(b, "Display status", s.format("ID_WEB_HauptMenuStatus_Zeile1") + " / " + s.format("ID_WEB_HauptMenuStatus_Zeile3")
                + " (" + s.format("ID_WEB_HauptMenuStatus_Zeile2") + " " + seconds(s, "ID_WEB_HauptMenuStatus_Zeit") + ")");
        line(b, "Operating state (BZ)", s.format("ID_WEB_WP_BZ_akt"));
        line(b, "Heating mode", s.format("ID_Ba_Hz_akt") + " | hot water mode: " + s.format("ID_Ba_Bw_akt")
                + " | cooling mode: " + s.format("ID_Einst_BA_Kuehl_akt"));
        line(b, "Compressor (VD1)", onOff(s, "ID_WEB_VD1out") + ", frequency " + s.format("ID_WEB_Freq_VD")
                + " (target " + s.format("ID_WEB_Freq_VD_Soll") + ")");
        line(b, "Pumps", "heating pump HUP " + onOff(s, "ID_WEB_HUPout") + " (" + s.format("HUP_PWM") + ")"
                + ", brine/well pump VBO " + onOff(s, "ID_WEB_VBOout")
                + ", circulation pump ZIP " + onOff(s, "ID_WEB_ZIPout") + ", DHW valve BUP " + onOff(s, "ID_WEB_BUPout"));
        line(b, "Electric heater (ZWE)", "ZWE1 " + onOff(s, "ID_WEB_ZW1out") + ", ZWE2 " + onOff(s, "ID_WEB_ZW2SSTout")
                + ", ZWE3 " + onOff(s, "ID_WEB_ZW3SSTout") + " | utility release EVU: " + onOff(s, "ID_WEB_EVUin"));
        line(b, "Last error", s.format("ID_WEB_ERROR_Nr0") + " at " + s.format("ID_WEB_ERROR_Time0")
                + " (" + s.format("ID_WEB_AnzahlFehlerInSpeicher") + " entries in memory, see luxtronik_get_errors)");
        line(b, "Last switch-off reason", s.format("ID_WEB_Switchoff_file_Nr0") + " at " + s.format("ID_WEB_Switchoff_file_Time0"));

        b.append("\n## Temperatures\n");
        line(b, "Flow TVL / return TRL", s.format("ID_WEB_Temperatur_TVL") + " / " + s.format("ID_WEB_Temperatur_TRL")
                + " (return setpoint " + s.format("ID_WEB_Sollwert_TRL_HZ") + ", external return sensor " + s.format("ID_WEB_Temperatur_TRL_ext") + ")");
        line(b, "Outdoor TA", s.format("ID_WEB_Temperatur_TA") + " (average " + s.format("ID_WEB_Mitteltemperatur") + ")");
        line(b, "Hot water TBW", s.format("ID_WEB_Temperatur_TBW") + " (target " + s.format("ID_WEB_Einst_BWS_akt") + ")");
        line(b, "Heat source in TWE / out TWA", s.format("ID_WEB_Temperatur_TWE") + " / " + s.format("ID_WEB_Temperatur_TWA")
                + " (brine circuit)");
        line(b, "Hot gas TVL_ext / suction", s.format("ID_WEB_Temperatur_THG") + " / " + s.format("ID_WEB_LIN_ANSAUG_VERDICHTER"));
        line(b, "Mixing circuit 1 flow", s.format("ID_WEB_Temperatur_TFB1") + " (setpoint " + s.format("ID_WEB_Sollwert_TVL_MK1") + ")");

        b.append("\n## Refrigerant circuit\n");
        line(b, "High / low pressure", s.format("ID_WEB_LIN_HD") + " / " + s.format("ID_WEB_LIN_ND"));
        line(b, "Superheat", s.format("ID_WEB_LIN_UH") + " (target " + s.format("ID_WEB_LIN_UH_Soll") + ")");
        line(b, "Evaporation / condensation temp", s.format("Vapourisation_Temperature") + " / " + s.format("Liquefaction_Temperature"));
        line(b, "Flow rate (heat meter)", s.format("ID_WEB_WMZ_Durchfluss") + ", heat source flow " + s.format("ID_WEB_Durchfluss_WQ"));
        line(b, "Electrical input (inverter)", s.format("AC_Power_Input"));

        b.append("\n## Settings\n");
        line(b, "Heating 'Temperatur +/-' (WK)", s.format("ID_Einst_WK_akt"));
        line(b, "Heating curve", "end point " + s.format("ID_Einst_HzHwHKE_akt") + ", parallel shift " + s.format("ID_Einst_HzHKRANH_akt")
                + ", night setback " + s.format("ID_Einst_HzHKRABS_akt"));
        line(b, "Mixing circuit 1 curve", "end point " + s.format("ID_Einst_HzMK1E_akt") + ", parallel shift " + s.format("ID_Einst_HzMK1ANH_akt")
                + ", night setback " + s.format("ID_Einst_HzMK1ABS_akt"));
        line(b, "Heating limit", "enabled " + s.format("ID_Einst_Heizgrenze") + ", temperature " + s.format("ID_Einst_Heizgrenze_Temp"));
        line(b, "Hot water", "target " + s.format("ID_Einst_BWS_akt") + ", hysteresis " + s.format("ID_Einst_BWS_Hyst_akt")
                + ", electric reheating " + s.format("ID_Einst_Warmwasser_Nachheizung"));
        line(b, "Cooling", "release above " + s.format("ID_Einst_KuehlFreig_akt") + " outdoor, MK1 setpoint " + s.format("ID_Sollwert_KuCft1_akt"));
        line(b, "Silent mode", s.format("SILENT_MODE"));

        b.append("\n## Counters\n");
        line(b, "Compressor VD1", hours(s, "ID_WEB_Zaehler_BetrZeitVD1") + ", " + s.format("ID_WEB_Zaehler_BetrZeitImpVD1") + " starts"
                + ", avg run " + s.format("ID_WEB_Time_VDStd_akt"));
        line(b, "Heat pump total / heating / hot water / cooling", hours(s, "ID_WEB_Zaehler_BetrZeitWP") + " / "
                + hours(s, "ID_WEB_Zaehler_BetrZeitHz") + " / " + hours(s, "ID_WEB_Zaehler_BetrZeitBW") + " / " + hours(s, "ID_WEB_Zaehler_BetrZeitKue"));
        line(b, "Electric heater ZWE1 / ZWE2", hours(s, "ID_WEB_Zaehler_BetrZeitZWE1") + " / " + hours(s, "ID_WEB_Zaehler_BetrZeitZWE2"));
        line(b, "Heat quantity heating / hot water / total", s.format("ID_WEB_WMZ_Heizung") + " / " + s.format("ID_WEB_WMZ_Brauchwasser")
                + " / " + s.format("ID_WEB_WMZ_Seit"));
        b.append("\nRegister names are the python-luxtronik names; use luxtronik_search_registers / luxtronik_get_registers for anything not listed here.\n");
        return b.toString();
    }

    private static void line(StringBuilder b, String label, String value) {
        b.append("- ").append(label).append(": ").append(value).append('\n');
    }

    private static String onOff(Snapshot s, String name) {
        Object v = s.value(name);
        if (v == null) {
            return "n/a";
        }
        if (v instanceof Boolean) {
            return (Boolean) v ? "on" : "off";
        }
        return String.valueOf(v);
    }

    private static String seconds(Snapshot s, String name) {
        Double d = s.number(name);
        return d == null ? "n/a" : humanDuration(d.longValue());
    }

    private static String hours(Snapshot s, String name) {
        Optional<RegisterDef> def = s.registers().find(RegisterKind.CALCULATIONS, name);
        if (def.isEmpty()) {
            return "n/a";
        }
        Object v = s.value(def.get());
        if (!(v instanceof Number)) {
            return "n/a";
        }
        long secs = ((Number) v).longValue();
        return String.format(Locale.ROOT, "%.0f h", secs / 3600.0);
    }

    static String humanDuration(long secs) {
        if (secs < 60) {
            return secs + " s";
        }
        if (secs < 3600) {
            return (secs / 60) + " min";
        }
        if (secs < 86400) {
            return String.format(Locale.ROOT, "%.1f h", secs / 3600.0);
        }
        return String.format(Locale.ROOT, "%.1f d", secs / 86400.0);
    }
}
