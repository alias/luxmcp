package luxmcp.lux;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritePolicyTest {

    private static final Registers REGS = Registers.load();
    private static final RegisterDef BWS = REGS.find(RegisterKind.PARAMETERS, "ID_Einst_BWS_akt").orElseThrow();
    private static final RegisterDef HZ_MODE = REGS.find(RegisterKind.PARAMETERS, "ID_Ba_Hz_akt").orElseThrow();

    @Test
    void disabledRefusesEverything() {
        WritePolicy.PolicyException e = assertThrows(WritePolicy.PolicyException.class,
                () -> WritePolicy.disabled().check(BWS, "45"));
        assertTrue(e.getMessage().contains("--allow-write"));
    }

    @Test
    void defaultsAllowEverydaySettingsWithinRange() throws Exception {
        WritePolicy p = WritePolicy.defaults();
        assertEquals(470, p.check(BWS, "47"));
        assertEquals(0, p.check(HZ_MODE, "Automatic"));
        WritePolicy.PolicyException tooHot = assertThrows(WritePolicy.PolicyException.class, () -> p.check(BWS, "75"));
        assertTrue(tooHot.getMessage().contains("30..60"), tooHot.getMessage());
        assertThrows(IllegalArgumentException.class, () -> p.check(HZ_MODE, "Turbo"));
    }

    @Test
    void notAllowlistedOrNotWriteableIsRefused() {
        WritePolicy p = WritePolicy.defaults();
        RegisterDef accessCode = REGS.find(RegisterKind.PARAMETERS, "ID_Einst_Zugangscode").orElseThrow();
        assertTrue(accessCode.writeable(), "precondition: upstream marks it writeable");
        WritePolicy.PolicyException e = assertThrows(WritePolicy.PolicyException.class, () -> p.check(accessCode, "1"));
        assertTrue(e.getMessage().contains("allowlist"));

        RegisterDef calc = REGS.find(RegisterKind.CALCULATIONS, "ID_WEB_Temperatur_TVL").orElseThrow();
        assertThrows(WritePolicy.PolicyException.class, () -> p.check(calc, "20"));

        // allowlisted by the user, but upstream does not know it as writeable
        RegisterDef heizgrenze = REGS.find(RegisterKind.PARAMETERS, "ID_Einst_Heizgrenze_Temp").orElseThrow();
        WritePolicy custom = WritePolicy.of(java.util.List.of(new WritePolicy.Rule("ID_Einst_Heizgrenze_Temp", 10.0, 30.0, null)));
        WritePolicy.PolicyException e2 = assertThrows(WritePolicy.PolicyException.class, () -> custom.check(heizgrenze, "20"));
        assertTrue(e2.getMessage().contains("not marked writeable"));
    }

    @Test
    void allowlistFile() throws Exception {
        Path f = Files.createTempFile("allowlist", ".txt");
        Files.writeString(f, "# comment\nID_Einst_BWS_akt 40 50 # hot water\n\nID_Ba_Bw_akt\n");
        WritePolicy p = WritePolicy.fromFile(f);
        assertEquals(2, p.rules().size());
        assertEquals("hot water", p.rules().get(0).note());
        assertEquals(450, p.check(BWS, "45"));
        assertThrows(WritePolicy.PolicyException.class, () -> p.check(BWS, "55"));
        assertThrows(WritePolicy.PolicyException.class, () -> p.check(HZ_MODE, "Off"));
        Files.deleteIfExists(f);
    }
}
