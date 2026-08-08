package io.github.lorenzovicino.ludus.tools;

import java.util.Locale;

/**
 * The status card for a GitHub profile README, as SVG.
 *
 * <p>SVG rather than HTML because a README has no choice. GitHub sanitises rendered Markdown — no
 * iframes, no scripts, no styles — so a live panel cannot be embedded. An image can, and an image
 * that is text and shapes rather than pixels stays sharp and weighs nothing.
 *
 * <p>Two files, light and dark, referenced from a {@code <picture>} element with
 * {@code prefers-color-scheme}. One card that only reads on a white background is half broken, and
 * the profile already uses this pattern for its language chart.
 *
 * <p>Everything is inline attributes: as an {@code <img>}, external CSS never loads and a
 * {@code <style>} block is unreliable across renderers.
 */
final class SvgCard {

    private static final int WIDTH = 520;
    private static final int HEIGHT = 150;
    private static final int CELL = 12;
    private static final int BOARD_X = 24;
    private static final int BOARD_Y = 27;
    private static final int TEXT_X = 144;

    private static final String FONT =
            "ui-sans-serif,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO =
            "ui-monospace,SFMono-Regular,Cascadia Mono,Consolas,monospace";

    record Palette(String ground, String rule, String ink, String inkSoft, String accent,
                   String squareLight, String squareDark) {
    }

    static final Palette LIGHT = new Palette(
            "#ffffff", "#d1d7e0", "#171b23", "#59626f", "#a2660f", "#e2e6ee", "#98a4b4");

    static final Palette DARK = new Palette(
            "#181b22", "#262b35", "#e6e9f0", "#949eae", "#dda344", "#3a414d", "#242a34");

    private SvgCard() {
    }

    static String render(Palette palette, StatusHistory.MatchResult match, int tests,
                         boolean perftVerified) {
        StringBuilder svg = new StringBuilder(4096);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(WIDTH)
                .append("\" height=\"").append(HEIGHT)
                .append("\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT)
                .append("\" role=\"img\" aria-label=\"")
                .append(StatusHistory.ENGINE).append(" engine status\">");

        svg.append("<rect x=\"0.5\" y=\"0.5\" width=\"").append(WIDTH - 1)
                .append("\" height=\"").append(HEIGHT - 1)
                .append("\" rx=\"4\" fill=\"").append(palette.ground())
                .append("\" stroke=\"").append(palette.rule()).append("\"/>");

        appendBoard(svg, palette);

        text(svg, TEXT_X, 44, 21, 660, palette.accent(), FONT, StatusHistory.ENGINE);
        text(svg, TEXT_X + 62, 44, 11, 500, palette.inkSoft(), MONO, StatusHistory.VERSION);
        text(svg, TEXT_X, 62, 11, 400, palette.inkSoft(), FONT,
                "A UCI chess engine in Java, in two acts");

        String headline = String.format(Locale.ROOT, "%+.0f ± %.0f Elo",
                match.elo(), match.margin());
        text(svg, TEXT_X, 104, 27, 620, palette.ink(), MONO, headline);

        String split = String.format(Locale.ROOT, "%s over %s  ·  %d-%d-%d in %d games",
                match.candidate(), match.baseline(),
                match.wins(), match.draws(), match.losses(), match.games());
        text(svg, TEXT_X, 122, 11, 400, palette.inkSoft(), MONO, split);

        String footer = String.format(Locale.ROOT, "%s  ·  %d tests green  ·  perft %s",
                StatusHistory.CURRENT_MILESTONE, tests, perftVerified ? "verified" : "MISMATCH");
        text(svg, TEXT_X, 138, 11, 400, palette.inkSoft(), MONO, footer);

        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * A plain eight by eight board as the mark. No pieces: at twelve pixels a glyph is a smudge, and
     * the alternation alone is unmistakable.
     */
    private static void appendBoard(StringBuilder svg, Palette palette) {
        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                boolean light = (rank + file) % 2 == 0;
                svg.append("<rect x=\"").append(BOARD_X + file * CELL)
                        .append("\" y=\"").append(BOARD_Y + rank * CELL)
                        .append("\" width=\"").append(CELL).append("\" height=\"").append(CELL)
                        .append("\" fill=\"")
                        .append(light ? palette.squareLight() : palette.squareDark())
                        .append("\"/>");
            }
        }
        svg.append("<rect x=\"").append(BOARD_X - 0.5).append("\" y=\"").append(BOARD_Y - 0.5)
                .append("\" width=\"").append(8 * CELL + 1).append("\" height=\"").append(8 * CELL + 1)
                .append("\" fill=\"none\" stroke=\"").append(palette.rule()).append("\"/>");
    }

    private static void text(StringBuilder svg, int x, int y, int size, int weight, String fill,
                             String family, String content) {
        svg.append("<text x=\"").append(x).append("\" y=\"").append(y)
                .append("\" font-family=\"").append(family)
                .append("\" font-size=\"").append(size)
                .append("\" font-weight=\"").append(weight)
                .append("\" fill=\"").append(fill).append("\">")
                .append(escape(content)).append("</text>");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
