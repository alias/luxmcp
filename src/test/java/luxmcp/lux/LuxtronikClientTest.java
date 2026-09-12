package luxmcp.lux;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuxtronikClientTest {

    private static final Registers REGS = Registers.load();

    static int[] sampleParameters() {
        int[] p = new int[1200];
        p[1] = 5;      // ID_Einst_WK_akt 0.5 °C
        p[2] = 455;    // ID_Einst_BWS_akt 45.5 °C
        p[3] = 2;      // ID_Ba_Hz_akt Party
        p[4] = 4;      // ID_Ba_Bw_akt Off
        p[11] = 370;   // heating curve end point 37.0
        return p;
    }

    static int[] sampleCalculations() {
        int[] c = new int[200];
        c[10] = 230;   // TVL 23.0
        c[11] = 231;   // TRL 23.1
        c[15] = -35;   // TA -3.5
        c[78] = 60;    // MSW 10
        String fw = "V3.92.3";
        for (int i = 0; i < 10; i++) {
            c[81 + i] = i < fw.length() ? fw.charAt(i) : 0;
        }
        c[95] = 1766568318; // ERROR_Time0
        c[100] = 715;       // high pressure switch-off
        c[117] = 0;         // heatpump running
        c[151] = 302552;    // WMZ_Heizung 30255.2 kWh
        c[180] = 1160;      // HD 11.60 bar
        c[173] = 0x7FFFFFFF; // Durchfluss_WQ not available
        return c;
    }

    @Test
    void readsAndDecodesAllVectors() throws Exception {
        try (FakeLuxtronik fake = new FakeLuxtronik(sampleParameters(), sampleCalculations(), new byte[]{1, 0, 1})) {
            LuxtronikClient client = new LuxtronikClient("127.0.0.1", fake.port());
            Snapshot s = new Snapshot(REGS, client.readAll());

            assertEquals(1200, s.length(RegisterKind.PARAMETERS));
            assertEquals(200, s.length(RegisterKind.CALCULATIONS));
            assertEquals(3, s.length(RegisterKind.VISIBILITIES));
            assertEquals(1, fake.connections.get(), "all three vectors are read over one connection");

            assertEquals(23.0, s.value("ID_WEB_Temperatur_TVL"));
            assertEquals("23.0 °C", s.format("ID_WEB_Temperatur_TVL"));
            assertEquals(-3.5, s.value("ID_WEB_Temperatur_TA"));
            assertEquals("Party", s.value("ID_Ba_Hz_akt"));
            assertEquals("Off", s.value("ID_Ba_Bw_akt"));
            assertEquals(0.5, s.value("ID_Einst_WK_akt"));
            assertEquals(45.5, s.value("ID_Einst_BWS_akt"));
            assertEquals("MSW 10", s.value("ID_WEB_Code_WP_akt"));
            assertEquals("V3.92.3", s.firmware());
            assertEquals("high pressure switch-off", s.value("ID_WEB_ERROR_Nr0"));
            assertEquals("heatpump running", s.value("ID_WEB_HauptMenuStatus_Zeile1"));
            assertEquals(30255.2, s.value("ID_WEB_WMZ_Heizung"));
            assertEquals("11.60 bar", s.format("ID_WEB_LIN_HD"));
            assertNull(s.value("ID_WEB_Durchfluss_WQ"), "0x7FFFFFFF means 'function not available'");
            assertEquals("n/a", s.format("ID_WEB_Durchfluss_WQ"));
            assertEquals(1L, REGS.find(RegisterKind.VISIBILITIES, 0).map(s::value).orElse(null), "visibilities are int8");
            assertNull(s.value("ID_WEB_Freq_VD"), "register 231 not sent by this (short) controller vector");
            // case-insensitive lookup
            assertEquals(23.0, s.value("id_web_temperatur_tvl"));
        }
    }

    @Test
    void writesParameterAndVerifies() throws Exception {
        try (FakeLuxtronik fake = new FakeLuxtronik(sampleParameters(), sampleCalculations(), new byte[]{1})) {
            LuxtronikClient client = new LuxtronikClient("127.0.0.1", fake.port());
            Path audit = Files.createTempFile("luxmcp-audit", ".log");
            HeatPump pump = new HeatPump(client, REGS, Duration.ofSeconds(10), WritePolicy.defaults(), audit);

            HeatPump.WriteResult r = pump.write("ID_Einst_BWS_akt", "47");
            assertTrue(r.verified());
            assertEquals("45.5 °C", r.before());
            assertEquals("47.0 °C", r.readBack());
            assertEquals(470, fake.parameters[2]);
            assertEquals(1, fake.writes.get());
            String log = Files.readString(audit);
            assertTrue(log.contains("ID_Einst_BWS_akt[2] 45.5 °C -> 47 (raw 470)"), log);

            // rate limit: second write right away is refused
            WritePolicy.PolicyException e = assertThrows(WritePolicy.PolicyException.class,
                    () -> pump.write("ID_Ba_Hz_akt", "Automatic"));
            assertTrue(e.getMessage().contains("wait at least"));
            assertEquals(1, fake.writes.get());
            Files.deleteIfExists(audit);
        }
    }

    @Test
    void unchangedValueDoesNotWrite() throws Exception {
        try (FakeLuxtronik fake = new FakeLuxtronik(sampleParameters(), sampleCalculations(), new byte[]{1})) {
            HeatPump pump = new HeatPump(new LuxtronikClient("127.0.0.1", fake.port()), REGS, Duration.ofSeconds(10),
                    WritePolicy.defaults(), null);
            HeatPump.WriteResult r = pump.write("ID_Ba_Hz_akt", "party");
            assertTrue(r.verified());
            assertEquals(0, fake.writes.get(), "value already set: no flash write");
        }
    }

    @Test
    void cacheIsReusedWithinTtl() throws Exception {
        try (FakeLuxtronik fake = new FakeLuxtronik(sampleParameters(), sampleCalculations(), new byte[]{1})) {
            HeatPump pump = new HeatPump(new LuxtronikClient("127.0.0.1", fake.port()), REGS, Duration.ofSeconds(30),
                    WritePolicy.disabled(), null);
            Snapshot a = pump.snapshot(false);
            Snapshot b = pump.snapshot(false);
            assertTrue(a == b);
            Snapshot c = pump.snapshot(true);
            assertFalse(a == c);
            assertEquals(2, fake.connections.get());
        }
    }

    @Test
    void connectionErrorIsDescriptive() {
        LuxtronikClient client = new LuxtronikClient("127.0.0.1", 1, 500, 500);
        IOException e = assertThrows(IOException.class, client::readAll);
        assertTrue(e.getMessage().contains("cannot connect"), e.getMessage());
    }
}
