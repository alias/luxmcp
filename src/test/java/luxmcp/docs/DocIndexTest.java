package luxmcp.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocIndexTest {

    private static DocIndex sample() throws Exception {
        Path dir = Files.createTempDirectory("luxmcp-docs");
        Files.writeString(dir.resolve("Regler_Teil1.txt"), """
                3
                Technische Änderungen vorbehalten  |  83055200lDE  |  ait-deutschland GmbH
                Inhaltsverzeichnis
                HEIZKURVEN EINSTELLEN  ....................1
                Heizkurven-Endpunkt festlegen .......................1
                TRINKWARMWASSERTEMPERATUR EINSTELLEN ......... 2
                """);
        DocIndex idx = DocIndex.load(dir);
        // simulate a two-page document with a TOC on page 1
        idx.addDocument("Regler_Teil2", List.of(
                """
                1
                Technische Änderungen vorbehalten  |  x  |  ait-deutschland GmbH
                Inhaltsverzeichnis
                HEIZKURVEN EINSTELLEN  ....................1
                FEHLERDIAGNOSE / FEHLERMELDUNGEN ......... 2
                Der Heizkurven-Endpunkt ist stets auf eine Außentemperatur von -20 °C bezogen.
                Die Temperaturwerte beziehen sich auf den Rücklauf.
                """,
                """
                2
                Technische Änderungen vorbehalten  |  x  |  ait-deutschland GmbH
                Fehlerdiagnose / Fehlermeldungen
                Nr. Anzeige Beschreibung Abhilfe
                715 Hochdruck-Abschalt.
                Reset automatisch
                Hochdruckpressostat im Kältekreis hat angesprochen.
                Durchfluss HW, Überströmer, Temperatur und Kondensation überprüfen.
                716 Hochdruckstörung
                Bitte Inst rufen
                Hochdruckpressostat im Kältekreis hat mehrfach angesprochen.
                """));
        return idx;
    }

    @Test
    void searchFindsHeatingCurveWithUmlautFolding() throws Exception {
        DocIndex idx = sample();
        List<DocIndex.Hit> hits = idx.search("Heizkurve Endpunkt Aussentemperatur", 5);
        assertFalse(hits.isEmpty());
        DocIndex.Chunk top = hits.get(0).chunk();
        assertEquals("Regler_Teil2", top.doc());
        assertEquals(1, top.page());
        assertTrue(top.heading().contains("HEIZKURVEN"), top.heading());
        assertTrue(top.text().contains("-20 °C"));
        assertTrue(idx.search("nichts passendes xyz", 5).isEmpty());
    }

    @Test
    void parsesErrorTable() throws Exception {
        DocIndex idx = sample();
        String e715 = idx.errorInfo(715);
        assertNotNull(e715);
        assertTrue(e715.startsWith("Hochdruck-Abschalt. Reset automatisch Hochdruckpressostat"), e715);
        assertTrue(e715.contains("Kondensation überprüfen"));
        assertTrue(idx.errorInfo(716).contains("mehrfach"));
        assertNull(idx.errorInfo(701));
    }

    @Test
    void pagesAndDocResolution() throws Exception {
        DocIndex idx = sample();
        assertEquals(2, idx.pageCount("Regler_Teil2"));
        assertEquals("Regler_Teil2", idx.resolveDoc("teil2"));
        assertNull(idx.resolveDoc("teil"), "ambiguous");
        assertTrue(idx.page("teil2", 2).contains("Fehlerdiagnose"));
        assertFalse(idx.page("teil2", 2).contains("Technische Änderungen vorbehalten"), "running header stripped");
        assertTrue(idx.page("teil2", 2).startsWith("Fehlerdiagnose"));
        assertNull(idx.page("teil2", 3));
    }
}
