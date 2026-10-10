package de.ethicbuilds.character.board;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.io.InputStream;

/** Breite von Texten der Schriften ethicbuilds:ui, ui_bold und ui_small in Text-Pixeln (wie Minecraft sie setzt). */
public final class TextMetrics {

    private static final double SPACE = 2.5;
    private final Font regular;
    private final Font bold;
    private final Font small;
    private final FontRenderContext context = new FontRenderContext(null, true, true);

    public TextMetrics() {
        this.regular = load("/fonts/outfit-500.ttf", 8.5f);
        this.bold = load("/fonts/outfit-700.ttf", 8.5f);
        this.small = load("/fonts/outfit-700.ttf", 6.5f);
    }

    private static Font load(String resource, float size) {
        try (InputStream in = TextMetrics.class.getResourceAsStream(resource)) {
            return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(size);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Breite in Text-Pixeln; font ist ethicbuilds:ui, ui_bold oder ui_small. */
    public double width(String text, String font) {
        Font f = switch (font) {
            case "ethicbuilds:ui_bold" -> bold;
            case "ethicbuilds:ui_small" -> small;
            default -> regular;
        };
        double size = f != null ? f.getSize2D() : 8.5;
        double width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                width += SPACE;
            } else if (f != null) {
                try {
                    GlyphVector vector = f.createGlyphVector(context, String.valueOf(c));
                    width += vector.getGlyphMetrics(0).getAdvance();
                } catch (Throwable e) {
                    width += size * 0.55;
                }
            } else {
                width += size * 0.55;
            }
        }
        return width;
    }
}
