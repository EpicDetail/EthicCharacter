package de.ethicbuilds.character.page.wiki;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.page.PageHandler;
import de.ethicbuilds.character.page.Prompts;
import de.ethicbuilds.character.page.wiki.WikiContent.Article;
import de.ethicbuilds.character.page.wiki.WikiContent.Hit;
import de.ethicbuilds.character.page.wiki.Rows.Seg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Seiten "wiki" (Kategorien, Artikel, Blaettern, Beliebt) und "wiki_suche" (Vorschlaege zur Eingabe). */
public final class WikiPage implements PageHandler {

    private static final String WIKI = "wiki";
    private static final String SEARCH = "wiki_suche";
    private static final String KAT = "wikiKat";
    private static final String SHEET = "wikiSeite";
    private static final String QUERY = "wikiSuche";
    private static final String HITS = "wikiTreffer";
    private static final int ROWS = 5;
    private static final int HEADING_PX = 20;
    private static final int PARAGRAPH_GAP = 10;
    private static final int HEADING_GAP = 14;
    private static final Pattern CATEGORY = Pattern.compile("w_kat_(\\d)");
    private static final Pattern CHIP = Pattern.compile("w_chip_(\\d)");
    private static final Pattern ROW = Pattern.compile("ws_zeile_(\\d)(_titel|_kat)?");
    private static final Pattern COLOR = Pattern.compile("^\\[(#[0-9A-Fa-f]{6})] ");

    /** Eine Zeile Fliesstext; gap ist der Abstand davor in px. */
    private record Line(String text, boolean bold, TextColor color, int gap) {
    }

    /** Eine Seite eines Artikels. */
    private record Sheet(Article article, List<Line> lines) {
    }

    private final Plugin plugin;
    private final Prompts prompts;
    private final Board board;
    private final WikiContent content;
    private final Map<Integer, List<Sheet>> sheets = new ConcurrentHashMap<>();

    public WikiPage(Plugin plugin, Prompts prompts, Board board, WikiContent content) {
        this.plugin = plugin;
        this.prompts = prompts;
        this.board = board;
        this.content = content;
    }

    @Override
    public Set<String> pages() {
        return Set.of(WIKI, SEARCH);
    }

    // ------------------------------------------------------------ Seiten bilden

    private Element element(String page, String id) {
        return board.layout().page(page).stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    // Alle Seiten einer Kategorie: jeder Artikel beginnt auf einer neuen Seite, so viele Zeilen wie in die Flaeche passen
    private List<Sheet> sheets(int category) {
        return sheets.computeIfAbsent(category, this::build);
    }

    private List<Sheet> build(int category) {
        Element area = element(WIKI, "w_text");
        List<Sheet> result = new ArrayList<>();
        int lineHeight = Integer.parseInt(area.extra("zeilenhoehePx", "26"));
        int available = (int) Math.floor(area.height() * 160);
        double width = area.width() - 0.05;
        for (Article article : content.articles(category)) {
            List<Line> current = new ArrayList<>();
            int used = 0;
            for (String paragraph : article.text()) {
                boolean heading = paragraph.startsWith("# ");
                TextColor color = Rows.TEXT;
                String text = heading ? paragraph.substring(2) : paragraph;
                Matcher m = COLOR.matcher(text);
                if (m.find()) {
                    color = TextColor.fromHexString(m.group(1));
                    text = text.substring(m.end());
                }
                int size = heading ? HEADING_PX : area.px();
                List<String> wrapped = board.wrap(text, heading ? Rows.BOLD : Rows.UI, size, width);
                for (int i = 0; i < wrapped.size(); i++) {
                    int gap = current.isEmpty() || i > 0 ? 0 : heading ? HEADING_GAP : PARAGRAPH_GAP;
                    // Eine Ueberschrift bleibt nicht allein am Seitenende
                    int need = gap + lineHeight * (heading && i == 0 ? 2 : 1);
                    if (!current.isEmpty() && used + need > available) {
                        result.add(new Sheet(article, current));
                        current = new ArrayList<>();
                        used = 0;
                        gap = 0;
                    }
                    current.add(new Line(wrapped.get(i), heading, color, gap));
                    used += gap + lineHeight;
                }
            }
            result.add(new Sheet(article, current));
        }
        return result;
    }

    private int firstSheet(int category, Article article) {
        List<Sheet> list = sheets(category);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).article() == article) {
                return i;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------ Zustand

    private static int number(Session s, String key) {
        return s.data().get(key) instanceof Number n ? n.intValue() : 0;
    }

    private int category(Session s) {
        return Math.max(0, Math.min(content.categories().size() - 1, number(s, KAT)));
    }

    private int sheet(Session s) {
        return Math.max(0, Math.min(sheets(category(s)).size() - 1, number(s, SHEET)));
    }

    @SuppressWarnings("unchecked")
    private static List<Hit> hits(Session s) {
        return s.data().get(HITS) instanceof List<?> list ? (List<Hit>) list : List.of();
    }

    private static String query(Session s) {
        return s.data().get(QUERY) instanceof String q ? q : "";
    }

    private static int index(Matcher m) {
        return Integer.parseInt(m.group(1));
    }

    // Eintraege, die das Layout in einem anderen Zustand zeichnet als er gerade gilt, legen wir selbst darueber
    private boolean ownCategory(Session s, Element e, int n) {
        return n == category(s) || "aktiv".equals(e.state());
    }

    private static boolean ownRow(Element e) {
        return !"normal".equals(e.state());
    }

    // ------------------------------------------------------------ Sichtbarkeit und Texte

    @Override
    public boolean visible(Session s, Element e) {
        String id = e.id();
        if (s.page().equals(WIKI)) {
            Matcher m = CATEGORY.matcher(id);
            if (m.matches()) {
                return !ownCategory(s, e, index(m));
            }
            return switch (id) {
                case "w_titel" -> false;
                case "w_zurueck" -> sheet(s) > 0;
                case "w_weiter" -> sheet(s) < sheets(category(s)).size() - 1;
                default -> true;
            };
        }
        int count = Math.min(ROWS, hits(s).size());
        Matcher m = ROW.matcher(id);
        if (m.matches()) {
            if (index(m) >= count) {
                return false;
            }
            return "_titel".equals(m.group(2)) ? false : "_kat".equals(m.group(2)) || !ownRow(e);
        }
        return switch (id) {
            case "ws_leer" -> hits(s).isEmpty();
            case "ws_fuss" -> !hits(s).isEmpty();
            default -> true;
        };
    }

    @Override
    public Component content(Session s, Element e) {
        String id = e.id();
        if (id.equals("w_seite") || id.equals("w_seite_tag")) {
            return Component.text("Seite " + (sheet(s) + 1) + " / " + sheets(category(s)).size());
        }
        if (id.equals("ws_feld")) {
            // Der Suchtext wird links buendig selbst gesetzt
            return Component.empty();
        }
        if (id.equals("ws_fuss")) {
            return Component.text(e.text().replace("{n}", String.valueOf(hits(s).size())));
        }
        Matcher m = ROW.matcher(id);
        if (m.matches() && "_kat".equals(m.group(2))) {
            List<Hit> hits = hits(s);
            if (index(m) < hits.size()) {
                String name = content.categories().get(hits.get(index(m)).article().category()).shortName();
                return Component.text(name);
            }
        }
        return null;
    }

    // ------------------------------------------------------------ Zeichnen

    @Override
    public void draw(Board b, Session s, String page) {
        if (page.equals(WIKI)) {
            drawWiki(b, s);
        } else {
            drawSearch(b, s);
        }
    }

    private void drawWiki(Board b, Session s) {
        int category = category(s);
        Sheet sheet = sheets(category).get(sheet(s));
        // Kategorie-Zeilen, die das Layout nicht richtig zeigt (aktive Kategorie, Beispielzustand)
        for (int n = 0; n < content.categories().size(); n++) {
            Element e = element(WIKI, "w_kat_" + n);
            if (e == null || !ownCategory(s, e, n)) {
                continue;
            }
            String image = e.images().get(n == category ? "aktiv" : "normal");
            if (image != null) {
                // Aktive Kategorie ohne Hover
                b.button(s, e.id(), e.x(), e.y(), e.width(), e.height(), "kategorie", image, n == category ? null : e.images().get("hover"));
            } else {
                b.region(s, e.id(), e.x(), e.y(), e.width(), e.height(), "kategorie");
            }
            Rows.draw(b, s, e.x(), e.y(), e.text(), false, Rows.TEXT, e.px(), Align.CENTER);
        }
        Element title = element(WIKI, "w_titel");
        String name = sheet.article().title();
        int size = Rows.fit(b, name, Rows.UI, title.px(), 20, title.width());
        Rows.draw(b, s, title.x() - title.width() / 2, title.y(), name, true, Rows.TEXT, size, Align.LEFT);
        Element area = element(WIKI, "w_text");
        double lineHeight = Integer.parseInt(area.extra("zeilenhoehePx", "26")) / 160.0;
        double y = area.y() + area.height() / 2;
        for (Line line : sheet.lines()) {
            y -= line.gap() / 160.0;
            int px = line.bold() ? HEADING_PX : area.px();
            Rows.draw(b, s, area.x() - area.width() / 2, y - lineHeight / 2, line.text(), line.bold(), line.color(), px, Align.LEFT);
            y -= lineHeight;
        }
    }

    private void drawSearch(Board b, Session s) {
        Element field = element(SEARCH, "ws_feld");
        double left = field.x() - field.width() / 2 + 0.06 + Double.parseDouble(field.extra("iconPx", "29")) / 160.0 + 0.1;
        Rows.draw(b, s, left, field.y(), query(s), false, Rows.TEXT, field.px(), Align.LEFT);
        // Links neue Suche, rechts das Kreuz zum Loeschen
        b.region(s, "ws_feld", field.x() - 0.25, field.y(), field.width() - 0.6, field.height(), "suche");
        double cross = field.x() + field.width() / 2 - 0.25;
        Rows.draw(b, s, cross, field.y(), "×", true, Rows.HINT, 24, Align.CENTER);
        b.region(s, "ws_loeschen", cross, field.y(), 0.3, field.height(), "loeschen");
        List<Hit> hits = hits(s);
        for (int n = 0; n < Math.min(ROWS, hits.size()); n++) {
            Element row = element(SEARCH, "ws_zeile_" + n);
            if (row == null) {
                continue;
            }
            if (ownRow(row)) {
                String image = row.images().get("normal");
                if (image != null) {
                    b.button(s, row.id(), row.x(), row.y(), row.width(), row.height(), "treffer", image, row.images().get("hover"));
                } else {
                    b.region(s, row.id(), row.x(), row.y(), row.width(), row.height(), "treffer");
                }
            }
            Element title = element(SEARCH, "ws_zeile_" + n + "_titel");
            Hit hit = hits.get(n);
            Rows.draw(b, s, title.x() - title.width() / 2, row.y(), segments(hit, title.px()), title.px(), Align.LEFT);
        }
    }

    // Titel in Stuecken: der Treffer-Teil fett und orange
    private static List<Seg> segments(Hit hit, int px) {
        String title = hit.article().title();
        List<Seg> segs = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= title.length(); i++) {
            if (i == title.length() || hit.mark()[i] != hit.mark()[start]) {
                boolean marked = hit.mark()[start];
                segs.add(new Seg(title.substring(start, i), marked, marked ? Rows.ORANGE : Rows.TEXT));
                start = i;
            }
        }
        return segs;
    }

    // ------------------------------------------------------------ Klicks

    @Override
    public boolean click(Board b, Session s, Element e) {
        String id = e.id();
        if (s.page().equals(WIKI)) {
            return clickWiki(b, s, id);
        }
        return clickSearch(b, s, id);
    }

    private boolean clickWiki(Board b, Session s, String id) {
        Matcher m = CATEGORY.matcher(id);
        if (m.matches()) {
            s.data().put(KAT, index(m));
            s.data().put(SHEET, 0);
            b.show(s, WIKI);
            return true;
        }
        m = CHIP.matcher(id);
        if (m.matches()) {
            List<Article> popular = content.popular();
            if (index(m) < popular.size()) {
                open(b, s, popular.get(index(m)));
            }
            return true;
        }
        switch (id) {
            case "w_suche" -> ask(b, s);
            case "w_zurueck" -> turn(b, s, -1);
            case "w_weiter" -> turn(b, s, 1);
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean clickSearch(Board b, Session s, String id) {
        switch (id) {
            case "ws_feld" -> ask(b, s);
            case "ws_loeschen" -> {
                s.data().remove(QUERY);
                s.data().remove(HITS);
                b.show(s, WIKI);
            }
            default -> {
                Matcher m = ROW.matcher(id);
                if (!m.matches() || m.group(2) != null) {
                    return false;
                }
                List<Hit> hits = hits(s);
                if (index(m) < hits.size()) {
                    open(b, s, hits.get(index(m)).article());
                }
            }
        }
        return true;
    }

    private void turn(Board b, Session s, int step) {
        int next = sheet(s) + step;
        if (next >= 0 && next < sheets(category(s)).size()) {
            s.data().put(SHEET, next);
            b.show(s, WIKI);
        }
    }

    // Artikel oeffnen: Kategorie waehlen, auf seine erste Seite springen
    private void open(Board b, Session s, Article article) {
        s.data().put(KAT, article.category());
        s.data().put(SHEET, firstSheet(article.category(), article));
        b.show(s, WIKI);
    }

    // Suchtext im Chat erfragen, danach die Trefferseite zeigen
    private void ask(Board b, Session s) {
        prompts.ask(s.player(), "Wonach suchst du?", text -> {
            if (b.session(s.player()) != s || text.isBlank()) {
                return;
            }
            s.data().put(QUERY, text);
            s.data().put(HITS, content.search(text));
            b.show(s, SEARCH);
        });
    }
}
