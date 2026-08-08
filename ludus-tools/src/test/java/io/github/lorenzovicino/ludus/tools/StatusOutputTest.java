package io.github.lorenzovicino.ludus.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.PerftSuite;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class StatusOutputTest {

    @Test
    void theTemplateExistsAndHasSomewhereToPutTheData() throws IOException {
        String template = readTemplate();
        assertTrue(template.contains("__STATUS_JSON__"),
                "The generator substitutes this placeholder; without it the page ships empty");
    }

    @Test
    void theTemplateIsACompleteDocument() throws IOException {
        // Without a doctype the browser falls back to quirks mode, where box-sizing and several
        // other rules behave differently and the layout quietly comes apart.
        String template = readTemplate();
        assertTrue(template.stripLeading().startsWith("<!doctype html>"),
                "A missing doctype means quirks mode");
        assertTrue(template.contains("<meta charset=\"utf-8\">"),
                "The page contains chess glyphs and typographic dashes");
        assertTrue(template.contains("lang=\""), "Screen readers need a language");
        assertTrue(template.contains("name=\"viewport\""), "It has to work on a phone");
    }

    @Test
    void theTemplateCarriesNoExternalReferences() throws IOException {
        // The page has to render from a filesystem and inside a sandbox that blocks other hosts, so
        // every byte it needs must already be in the file.
        String template = readTemplate();
        assertTrue(!template.contains("<link"), "No external stylesheets");
        assertTrue(!template.contains("src=\"http"), "No remote scripts or images");
        assertTrue(!template.contains("@import"), "No imported stylesheets");
        assertTrue(!template.contains("fonts.googleapis"), "No webfont host");
    }

    @Test
    void theTemplateDefinesBothThemes() throws IOException {
        String template = readTemplate();
        // A colour defined only inside a media query never applies in the un-stamped "system" state,
        // which is the classic way a page ends up with one theme's text on the other's background.
        assertTrue(template.contains("prefers-color-scheme: dark"), "Dark for the system default");
        assertTrue(template.contains("[data-theme=\"dark\"]"), "Dark for an explicit choice");
        assertTrue(template.contains("[data-theme=\"light\"]"), "Light must be able to beat a dark OS");
        assertTrue(template.contains("background: var(--ground)"),
                "The body needs an explicit background or it borrows the host's");
    }

    @Test
    void bothCardsCarryTheHeadlineNumbers() {
        StatusHistory.MatchResult match = StatusHistory.LATEST_MATCH;
        for (SvgCard.Palette palette : List.of(SvgCard.LIGHT, SvgCard.DARK)) {
            String svg = SvgCard.render(palette, match, 138, true);
            assertTrue(svg.startsWith("<svg "), "Must be an SVG document");
            assertTrue(svg.endsWith("</svg>"));
            assertTrue(svg.contains("ludus"));
            assertTrue(svg.contains("Elo"), () -> "The headline is the Elo figure: " + svg);
            assertTrue(svg.contains("186-12-2"), "The raw split belongs on the card");
            assertTrue(svg.contains("138 tests green"));
            assertTrue(svg.contains("perft verified"));
        }
    }

    @Test
    void theTwoCardsDifferOnlyInColour() {
        String light = SvgCard.render(SvgCard.LIGHT, StatusHistory.LATEST_MATCH, 138, true);
        String dark = SvgCard.render(SvgCard.DARK, StatusHistory.LATEST_MATCH, 138, true);

        assertNotEquals(light, dark, "A single card would be unreadable in one of the two themes");
        assertTrue(light.contains(SvgCard.LIGHT.ground()));
        assertTrue(dark.contains(SvgCard.DARK.ground()));
        assertEquals(countSquares(light), countSquares(dark), "Same board, different palette");
    }

    @Test
    void theCardUsesAPointForDecimalsWhateverTheLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ITALY);
            String svg = SvgCard.render(SvgCard.LIGHT, StatusHistory.LATEST_MATCH, 138, true);
            assertTrue(!svg.contains("552,"), () -> "Locale leaked into the card: " + svg);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void aMismatchIsSaidOutLoud() {
        String svg = SvgCard.render(SvgCard.LIGHT, StatusHistory.LATEST_MATCH, 138, false);
        assertTrue(svg.contains("MISMATCH"),
                "A card that quietly said 'verified' after a failed perft would be the worst outcome");
    }

    @Test
    void everyMilestoneIsDescribedAndUnique() {
        List<StatusHistory.Milestone> milestones = StatusHistory.MILESTONES;
        assertTrue(milestones.size() >= 7);
        assertEquals(milestones.size(), milestones.stream().map(StatusHistory.Milestone::id).distinct().count(),
                "Duplicate ids would collide in the ladder");
        for (StatusHistory.Milestone milestone : milestones) {
            assertTrue(!milestone.title().isBlank(), () -> milestone.id() + " needs a title");
            assertTrue(!milestone.criterion().isBlank(),
                    () -> milestone.id() + " needs an exit criterion, or it cannot be called done");
        }
    }

    @Test
    void theCurrentMilestoneIsOneThatIsDone() {
        assertTrue(StatusHistory.MILESTONES.stream()
                        .anyMatch(m -> m.id().equals(StatusHistory.CURRENT_MILESTONE) && m.done()),
                "The page would claim a milestone that its own ladder shows as planned");
    }

    @Test
    void everyPerftPositionOnThePageHasAParseableBoard() {
        // The page draws each of these from its FEN, so an unparseable one would render an empty grid.
        List<PerftSuite.Position> positions = PerftSuite.positions();
        assertEquals(6, positions.size());
        for (PerftSuite.Position position : positions) {
            Board board = Board.fromFen(position.fen());
            assertNotNull(board.toFen());
            assertTrue(!position.cases().isEmpty(), () -> position.name() + " has no cases");
        }
    }

    private static long countSquares(String svg) {
        return svg.lines().flatMap(line -> line.chars().boxed()).count() > 0
                ? svg.split("<rect", -1).length
                : 0;
    }

    private static String readTemplate() throws IOException {
        try (InputStream in = StatusMain.class.getResourceAsStream("/status-page.html")) {
            assertNotNull(in, "status-page.html must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
