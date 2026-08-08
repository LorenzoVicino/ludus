package io.github.lorenzovicino.ludus.tools;

import java.util.Locale;

/**
 * A small JSON writer, enough for the status document and no more. Adding a JSON library to reach
 * this would be more dependency than the job deserves.
 *
 * <p>Every number is formatted with {@link Locale#ROOT}, and that is not pedantry. This project is
 * developed on a machine with an Italian locale, where the default formatter writes {@code 552,1} —
 * which is not a JSON number, and would produce a page that silently fails to parse. The match
 * runner's console output shows the comma; this file is where it must not appear.
 */
final class Json {

    private final StringBuilder out = new StringBuilder(8192);
    private boolean needsComma;

    Json beginObject() {
        separate();
        out.append('{');
        needsComma = false;
        return this;
    }

    Json endObject() {
        out.append('}');
        needsComma = true;
        return this;
    }

    Json beginArray() {
        separate();
        out.append('[');
        needsComma = false;
        return this;
    }

    Json endArray() {
        out.append(']');
        needsComma = true;
        return this;
    }

    Json name(String name) {
        separate();
        quote(name);
        out.append(':');
        needsComma = false;
        return this;
    }

    Json value(String value) {
        separate();
        quote(value);
        needsComma = true;
        return this;
    }

    Json value(long value) {
        separate();
        out.append(value);
        needsComma = true;
        return this;
    }

    Json value(boolean value) {
        separate();
        out.append(value);
        needsComma = true;
        return this;
    }

    Json value(double value, int decimals) {
        separate();
        out.append(String.format(Locale.ROOT, "%." + decimals + "f", value));
        needsComma = true;
        return this;
    }

    Json field(String name, String value) {
        return name(name).value(value);
    }

    Json field(String name, long value) {
        return name(name).value(value);
    }

    Json field(String name, boolean value) {
        return name(name).value(value);
    }

    Json field(String name, double value, int decimals) {
        return name(name).value(value, decimals);
    }

    private void separate() {
        if (needsComma) {
            out.append(',');
        }
    }

    private void quote(String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
