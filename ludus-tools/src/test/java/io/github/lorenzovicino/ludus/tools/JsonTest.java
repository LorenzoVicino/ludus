package io.github.lorenzovicino.ludus.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JsonTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    void writesAFlatObject() {
        String json = new Json().beginObject()
                .field("engine", "ludus")
                .field("tests", 138)
                .field("green", true)
                .endObject()
                .toString();
        assertEquals("{\"engine\":\"ludus\",\"tests\":138,\"green\":true}", json);
    }

    @Test
    void writesNestedObjectsAndArrays() {
        String json = new Json().beginObject()
                .name("nps").beginObject().field("min", 36L).field("max", 55L).endObject()
                .name("depths").beginArray().value(1).value(2).value(3).endArray()
                .endObject()
                .toString();
        assertEquals("{\"nps\":{\"min\":36,\"max\":55},\"depths\":[1,2,3]}", json);
    }

    @Test
    void separatesObjectsInsideAnArray() {
        String json = new Json().beginArray()
                .beginObject().field("id", "M0").endObject()
                .beginObject().field("id", "M1").endObject()
                .endArray()
                .toString();
        assertEquals("[{\"id\":\"M0\"},{\"id\":\"M1\"}]", json);
    }

    @Test
    void decimalsUseAPointWhateverTheSystemLocaleSays() {
        // The whole reason this class formats with Locale.ROOT. On an Italian locale the default
        // formatter writes 552,1 — which turns the generated page into a parse error, silently.
        Locale.setDefault(Locale.ITALY);
        String json = new Json().beginObject().field("elo", 552.1, 1).endObject().toString();
        assertEquals("{\"elo\":552.1}", json);
    }

    @Test
    void escapesWhatWouldBreakTheDocument() {
        String json = new Json().beginObject()
                .field("text", "a \"quoted\" back\\slash\nnewline")
                .endObject()
                .toString();
        assertEquals("{\"text\":\"a \\\"quoted\\\" back\\\\slash\\nnewline\"}", json);
    }

    @Test
    void escapesControlCharacters() {
        String json = new Json().beginObject().field("text", "bell").endObject().toString();
        assertTrue(json.contains("\\u0007"), () -> "Control characters must be escaped: " + json);
    }

    @Test
    void fenStringsSurviveUnchanged() {
        // FENs carry slashes and hyphens; neither needs escaping, and escaping them would be wrong.
        String fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
        String json = new Json().beginObject().field("fen", fen).endObject().toString();
        assertEquals("{\"fen\":\"" + fen + "\"}", json);
    }
}
