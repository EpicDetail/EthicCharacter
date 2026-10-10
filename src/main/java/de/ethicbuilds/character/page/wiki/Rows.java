package de.ethicbuilds.character.page.wiki;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

import java.util.List;

/** Textzeilen aus mehreren Stuecken (Farbe, normal oder fett) auf der Tafel, links, mittig oder rechts buendig. */
final class Rows {

    static final String UI = "ethicbuilds:ui";
    static final String BOLD = "ethicbuilds:ui_bold";
    static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    static final TextColor ORANGE = TextColor.fromHexString("#F9A11E");

    record Seg(String text, boolean bold, TextColor color) {
    }

    private Rows() {
    }

    static Seg seg(String text, TextColor color) {
        return new Seg(text, false, color);
    }

    static void draw(Board board, Session s, double x, double y, List<Seg> segs, int px, Align align) {
        double width = 0;
        for (Seg seg : segs) {
            width += board.measure(seg.text(), seg.bold() ? BOLD : UI, px);
        }
        double center = switch (align) {
            case LEFT -> x + width / 2;
            case RIGHT -> x - width / 2;
            default -> x;
        };
        TextComponent.Builder root = Component.text();
        for (Seg seg : segs) {
            root.append(Component.text(seg.text()).color(seg.color()).font(Key.key(seg.bold() ? BOLD : UI)));
        }
        board.text(s, center, y, root.build(), px);
    }

    static void draw(Board board, Session s, double x, double y, String text, boolean bold, TextColor color, int px, Align align) {
        draw(board, s, x, y, List.of(new Seg(text, bold, color)), px, align);
    }

    /** Groesste Schrifthoehe zwischen min und px, bei der der Text in width Bloecke passt. */
    static int fit(Board board, String text, String font, int px, int min, double width) {
        int size = px;
        while (size > min && board.measure(text, font, size) > width) {
            size--;
        }
        return size;
    }
}
