package de.ethicbuilds.character.page.wiki;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.page.PageHandler;
import de.ethicbuilds.character.page.wiki.Rows.Seg;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Seite "willkommen": Begruessung mit Haken "nie wieder anzeigen", Knopf zum Wiki und Schliessen. */
public final class WelcomePage implements PageHandler {

    private static final String PAGE = "willkommen";
    private static final String NEVER = "wkNie";
    private static final String HINT_KEY = "/charakter";
    private static final TextColor TEXT = TextColor.fromHexString("#C3CCD8");

    private final Board board;
    private final WelcomeStore store;

    public WelcomePage(Board board, WelcomeStore store) {
        this.board = board;
        this.store = store;
    }

    @Override
    public Set<String> pages() {
        return Set.of(PAGE);
    }

    private Element element(String id) {
        return board.layout().page(PAGE).stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    private static boolean checked(Session s) {
        return Boolean.TRUE.equals(s.data().get(NEVER));
    }

    @Override
    public boolean visible(Session s, Element e) {
        return switch (e.id()) {
            // Diese Texte setzen wir links buendig selbst, der angehakte Haken kommt aus dem Layout-Bild darueber
            case "wk_kicker", "wk_titel", "wk_hinweis_text", "wk_haken_text" -> false;
            case "wk_haken" -> !checked(s);
            default -> true;
        };
    }

    @Override
    public void draw(Board b, Session s, String page) {
        Element kicker = element("wk_kicker");
        Rows.draw(b, s, kicker.x() - kicker.width() / 2, kicker.y(), kicker.text(), true, kicker.color(), kicker.px(), Align.LEFT);
        Element title = element("wk_titel");
        int size = Rows.fit(b, title.text(), Rows.BOLD, title.px(), 24, title.width());
        Rows.draw(b, s, title.x() - title.width() / 2, title.y(), title.text(), true, title.color(), size, Align.LEFT);
        body(b, s);
        hint(b, s);
        Element tick = element("wk_haken");
        if (checked(s)) {
            String image = tick.images().get("angehakt");
            if (image != null) {
                b.glyph(s, tick.x(), tick.y(), image, null);
            }
            b.region(s, tick.id(), tick.x(), tick.y(), tick.width(), tick.height(), "haken");
        }
        Element label = element("wk_haken_text");
        Rows.draw(b, s, label.x() - label.width() / 2, label.y(), label.text(), false, label.color(), label.px(), Align.LEFT);
        b.region(s, label.id(), label.x(), label.y(), label.width(), label.height(), "haken");
    }

    // Fliesstext Zeile fuer Zeile in die Flaeche
    private void body(Board b, Session s) {
        Element area = element("wk_text");
        String name = String.valueOf(s.data().getOrDefault("name", ""));
        int lineHeight = Integer.parseInt(area.extra("zeilenhoehePx", "28"));
        int max = (int) Math.floor(area.height() * 160 / lineHeight);
        List<String> lines = b.wrap(area.text().replace("{name}", name), Rows.UI, area.px(), area.width() - 0.05);
        double y = area.y() + area.height() / 2;
        for (int i = 0; i < Math.min(max, lines.size()); i++) {
            double center = y - (i + 0.5) * lineHeight / 160.0;
            Rows.draw(b, s, area.x() - area.width() / 2, center, lines.get(i), false, TEXT, area.px(), Align.LEFT);
        }
    }

    // Hinweiskarte: bis zu zwei Zeilen, "/charakter" hervorgehoben
    private void hint(Board b, Session s) {
        Element e = element("wk_hinweis_text");
        List<String> lines = b.wrap(e.text(), Rows.UI, e.px(), e.width());
        int count = Math.min(2, lines.size());
        double step = e.px() * 1.3 / 160.0;
        for (int i = 0; i < count; i++) {
            double y = e.y() + (count - 1) * step / 2 - i * step;
            Rows.draw(b, s, e.x() - e.width() / 2, y, highlight(lines.get(i)), e.px(), Align.LEFT);
        }
    }

    private static List<Seg> highlight(String line) {
        List<Seg> segs = new ArrayList<>();
        int at = line.indexOf(HINT_KEY);
        if (at < 0) {
            segs.add(Rows.seg(line, Rows.HINT));
            return segs;
        }
        segs.add(Rows.seg(line.substring(0, at), Rows.HINT));
        segs.add(new Seg(HINT_KEY, true, Rows.TEXT));
        segs.add(Rows.seg(line.substring(at + HINT_KEY.length()), Rows.HINT));
        return segs;
    }

    @Override
    public boolean click(Board b, Session s, Element e) {
        switch (e.id()) {
            case "wk_haken", "wk_haken_text" -> {
                s.data().put(NEVER, !checked(s));
                b.refresh(s);
            }
            case "wk_wiki" -> {
                remember(s);
                b.show(s, "wiki");
            }
            case "wk_schliessen" -> {
                remember(s);
                b.close(s.player());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // Haken gesetzt: fuer immer merken
    private void remember(Session s) {
        if (checked(s)) {
            store.add(s.player().getUniqueId());
        }
    }
}
