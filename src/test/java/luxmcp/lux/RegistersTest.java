package luxmcp.lux;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistersTest {

    private static final Registers REGS = Registers.load();

    @Test
    void loadsGeneratedTable() {
        assertTrue(REGS.all(RegisterKind.CALCULATIONS).size() > 250);
        assertTrue(REGS.all(RegisterKind.PARAMETERS).size() > 1100);
        assertTrue(REGS.all(RegisterKind.VISIBILITIES).size() > 350);
        assertTrue(REGS.sourceInfo().startsWith("python-luxtronik"));
    }

    @Test
    void lookups() {
        RegisterDef tvl = REGS.find(RegisterKind.CALCULATIONS, "ID_WEB_Temperatur_TVL").orElseThrow();
        assertEquals(10, tvl.index());
        assertEquals("Celsius", tvl.type().name());
        assertFalse(tvl.writeable());
        assertEquals(tvl, REGS.find("id_web_temperatur_tvl").orElseThrow());
        assertEquals(tvl, REGS.find(RegisterKind.CALCULATIONS, "10").orElseThrow());

        RegisterDef bws = REGS.find(RegisterKind.PARAMETERS, "ID_Einst_BWS_akt").orElseThrow();
        assertEquals(2, bws.index());
        assertTrue(bws.writeable());

        // last definition for an index wins, like upstream (81 = SoftStand version and SoftStand_0 char)
        RegisterDef c81 = REGS.find(RegisterKind.CALCULATIONS, 81).orElseThrow();
        assertEquals("ID_WEB_SoftStand_0", c81.name());
        RegisterDef version = REGS.find("ID_WEB_SoftStand").orElseThrow();
        assertEquals(10, version.count());

        // aliases resolve
        assertEquals("HUP_PWM", REGS.find("Circulation_Pump").orElseThrow().name());
        assertTrue(REGS.find("does_not_exist").isEmpty());
        assertTrue(REGS.find("10").isEmpty(), "numeric index without kind is ambiguous");
    }

    @Test
    void curatedNotesAreMergedIntoDescriptions() {
        RegisterDef wk = REGS.find("ID_Einst_WK_akt").orElseThrow();
        assertTrue(wk.description().contains("Temperatur +/-"), wk.description());
        assertEquals(wk, REGS.find(RegisterKind.PARAMETERS, 1).orElseThrow(), "index lookup sees the enriched definition");
        assertTrue(REGS.all(RegisterKind.PARAMETERS).contains(wk));
        // searchable through the note text
        assertTrue(REGS.search("Temperaturabweichung", RegisterKind.PARAMETERS, false, false).contains(wk));
    }

    @Test
    void search() {
        List<RegisterDef> hits = REGS.search("temperatur tvl", null, false, false);
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(d -> d.name().equals("ID_WEB_Temperatur_TVL")));
        assertTrue(hits.stream().noneMatch(RegisterDef::isUnknown));

        List<RegisterDef> writeable = REGS.search("ba_", RegisterKind.PARAMETERS, false, true);
        assertTrue(writeable.stream().allMatch(RegisterDef::writeable));
        assertTrue(writeable.stream().anyMatch(d -> d.name().equals("ID_Ba_Hz_akt")));

        assertTrue(REGS.search("unknown_calculation_0", RegisterKind.CALCULATIONS, false, false).isEmpty());
        assertFalse(REGS.search("unknown_calculation_0", RegisterKind.CALCULATIONS, true, false).isEmpty());
    }
}
