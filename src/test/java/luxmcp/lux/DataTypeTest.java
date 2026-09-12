package luxmcp.lux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataTypeTest {

    private static final Registers REGS = Registers.load();

    @Test
    void scalingRoundTrips() {
        DataType celsius = REGS.datatype("Celsius");
        assertEquals(23.0, celsius.decode(new int[]{230}));
        assertEquals(-3.5, celsius.decode(new int[]{-35}));
        assertEquals("-3.5 °C", celsius.format(new int[]{-35}));
        assertEquals(455, celsius.encode("45.5"));
        assertEquals(455, celsius.encode("45,5 °C"));
        assertEquals(-20, celsius.encode("-2"));

        DataType pressure = REGS.datatype("Pressure");
        assertEquals(12.01, pressure.decode(new int[]{1201}));
        assertEquals("12.01 bar", pressure.format(new int[]{1201}));

        DataType energy = REGS.datatype("Energy");
        assertEquals("30255.2 kWh", energy.format(new int[]{302552}));
    }

    @Test
    void selectionRoundTrips() {
        DataType mode = REGS.datatype("HeatingMode");
        assertEquals("Automatic", mode.decode(new int[]{0}));
        assertEquals("Unknown_9", mode.decode(new int[]{9}));
        assertEquals(2, mode.encode("Party"));
        assertEquals(2, mode.encode("party"));
        assertEquals(1, mode.encode("SECOND heatsource"));
        assertEquals(3, mode.encode("3"));
        assertEquals(9, mode.encode("Unknown_9"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mode.encode("Turbo"));
        assertTrue(e.getMessage().contains("Automatic"), e.getMessage());
    }

    @Test
    void specialTypes() {
        assertEquals(true, REGS.datatype("Bool").decode(new int[]{1}));
        assertEquals(1, REGS.datatype("Bool").encode("on"));
        assertEquals(0, REGS.datatype("Bool").encode("false"));
        assertEquals("192.168.10.41", REGS.datatype("IPv4Address").decode(new int[]{(192 << 24) | (168 << 16) | (10 << 8) | 41}));
        assertEquals("V3.92.3", REGS.datatype("Version").decode(new int[]{'V', '3', '.', '9', '2', '.', '3', 0, 0, 0}));
        assertEquals("3.92", REGS.datatype("MajorMinorVersion").decode(new int[]{392}));
        assertEquals(2.5, REGS.datatype("Hours2").decode(new int[]{3}));
        assertEquals(3, REGS.datatype("Hours2").encode("2.5"));
        assertEquals("6:30", REGS.datatype("TimeOfDay").decode(new int[]{6 * 3600 + 30 * 60}));
        assertEquals(6 * 3600 + 30 * 60, REGS.datatype("TimeOfDay").encode("6:30"));
        int range = ((22 * 60 + 15) << 16) + (6 * 60);
        assertEquals("6:00-22:15", REGS.datatype("TimeOfDay2").decode(new int[]{range}));
        assertEquals(range, REGS.datatype("TimeOfDay2").encode("6:00-22:15"));
        assertEquals("high pressure switch-off", REGS.datatype("Errorcode").decode(new int[]{715}));
        assertEquals("", REGS.datatype("Character").decode(new int[]{0}));
        assertEquals("A", REGS.datatype("Character").decode(new int[]{'A'}));
        assertEquals(42L, REGS.datatype("Unknown").decode(new int[]{42}));
        assertEquals("1500 rpm", REGS.datatype("Speed").format(new int[]{1500}));
    }

    @Test
    void timestampNullForZero() {
        assertEquals(null, REGS.datatype("Timestamp").decode(new int[]{0}));
        assertEquals("n/a", REGS.datatype("Timestamp").format(new int[]{0}));
        assertTrue(String.valueOf(REGS.datatype("Timestamp").decode(new int[]{1766568318})).startsWith("2025-12-24"));
    }
}
