package de.ethicbuilds.character.page.log;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.page.PageHandler;
import de.ethicbuilds.economy.format.CoinFormat;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Seite "protokoll": Coin-Protokoll aller Spieler (nur Team), 8 Zeilen je Seite, Filter nach Quelle. */
public final class ProtokollPage implements PageHandler {

    private static final int ROWS = 8;
    private static final String FONT = "ethicbuilds:ui";
    private static final String BOLD = "ethicbuilds:ui_bold";
    private static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    private static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    private static final TextColor ORANGE = TextColor.fromHexString("#F9A11E");
    private static final TextColor GREEN = TextColor.fromHexString("#2DBE6C");
    private static final TextColor RED = TextColor.fromHexString("#E05252");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.of("Europe/Berlin"));

    // Lage in Bloecken (Karte p_karte: Mitte 0.887 / -0.269, 5.125 x 3.663)
    private static final double CARD_X = 0.887;
    private static final double LEFT = -1.48;
    private static final double AMOUNT_RIGHT = 0.80;
    private static final double SOURCE_LEFT = 1.10;
    private static final double TIME_RIGHT = 3.26;
    private static final double HEADER_Y = 1.37;
    private static final double FIRST_ROW_Y = 1.10;
    private static final double ROW_STEP = 0.3125;
    private static final double FOOTER_Y = -1.78;
    private static final double NAME_WIDTH = 1.75;

    // Schluessel in session.data()
    private static final String K_ROWS = "p_rows";
    private static final String K_TOTAL = "p_total";
    private static final String K_PAGE = "p_page";
    private static final String K_FILTER = "p_filter";
    private static final String K_SOURCES = "p_sources";
    private static final String K_MISSING = "p_fehlt";
    private static final String K_BUSY = "p_busy";

    /** Eine Zeile der Anzeige, unabhaengig von der Klasse der EthicEconomy-Schnittstelle. */
    private record Row(UUID player, String name, long amount, String source, Instant time) {
    }

    private record Result(List<Row> rows, long total) {
    }

    private final Plugin plugin;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public ProtokollPage(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Set<String> pages() {
        return Set.of("protokoll");
    }

    // ------------------------------------------------------------ Laden (Hintergrund)

    @Override
    public void load(Player player, Map<String, Object> data, String page) {
        data.put(K_PAGE, 0);
        data.put(K_FILTER, "Alle");
        data.remove(K_BUSY);
        data.put(K_ROWS, List.<Row>of());
        data.put(K_TOTAL, 0L);
        if (!Boolean.TRUE.equals(data.get("team"))) {
            return;
        }
        List<String> sources = Source.sources(plugin);
        if (sources == null) {
            data.put(K_MISSING, true);
            return;
        }
        data.remove(K_MISSING);
        List<String> filters = new ArrayList<>();
        filters.add("Alle");
        filters.addAll(sources);
        data.put(K_SOURCES, filters);
        Result result = fetch(0, "Alle");
        data.put(K_ROWS, result.rows());
        data.put(K_TOTAL, result.total());
    }

    // Holt eine Seite samt Spielernamen, nur im Hintergrund aufrufen
    private Result fetch(int page, String filter) {
        Source.Page found = Source.history(plugin, page * ROWS, ROWS, filter.equals("Alle") ? null : filter);
        if (found == null) {
            return new Result(List.of(), 0);
        }
        List<Row> rows = new ArrayList<>();
        for (Source.Line line : found.lines()) {
            rows.add(new Row(line.player(), name(line.player()), line.amount(), line.source(), line.time()));
        }
        return new Result(rows, found.total());
    }

    private String name(UUID id) {
        return names.computeIfAbsent(id, key -> {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(key);
            String name = offline.getName();
            return name != null ? name : key.toString().substring(0, 8);
        });
    }

    // ------------------------------------------------------------ Zeichnen (Hauptthread)

    @Override
    public void draw(Board board, Session s, String page) {
        if (!Boolean.TRUE.equals(s.data().get("team"))) {
            note(board, s, "Nur für das Team");
            return;
        }
        if (Boolean.TRUE.equals(s.data().get(K_MISSING))) {
            note(board, s, "Verlauf nicht verfügbar");
            return;
        }
        header(board, s, "Spieler", LEFT, Align.LEFT);
        header(board, s, "Betrag", AMOUNT_RIGHT, Align.RIGHT);
        header(board, s, "Quelle", SOURCE_LEFT, Align.LEFT);
        header(board, s, "Zeitpunkt", TIME_RIGHT, Align.RIGHT);
        List<Row> rows = rows(s);
        for (int i = 0; i < rows.size(); i++) {
            double y = FIRST_ROW_Y - i * ROW_STEP;
            if (i % 2 == 0) {
                board.glyph(s, CARD_X, y, "zeile_792x50_r16_28-51-80", null);
            }
            row(board, s, rows.get(i), y);
        }
        if (rows.isEmpty()) {
            board.text(s, CARD_X, 0.2, Component.text("Keine Einträge").color(HINT).font(Key.key(FONT)), 22);
        }
        footer(board, s);
    }

    private void note(Board board, Session s, String text) {
        board.text(s, CARD_X, -0.2, Component.text(text).color(HINT).font(Key.key(FONT)), 22);
    }

    private void header(Board board, Session s, String text, double x, Align align) {
        board.line(s, x, HEADER_Y, Component.text(text).color(HINT), text, FONT, 15, align);
    }

    private void row(Board board, Session s, Row row, double y) {
        String name = fit(board, row.name(), NAME_WIDTH);
        board.line(s, LEFT, y, Component.text(name).color(TEXT), name, FONT, 20, Align.LEFT);
        String amount = (row.amount() >= 0 ? "+" : "-") + CoinFormat.number(Math.abs(row.amount()));
        board.line(s, AMOUNT_RIGHT, y, Component.text(amount).color(row.amount() >= 0 ? GREEN : RED), amount, BOLD, 20, Align.RIGHT);
        board.line(s, SOURCE_LEFT, y, Component.text(row.source()).color(HINT), row.source(), FONT, 20, Align.LEFT);
        String time = TIME.format(row.time());
        board.line(s, TIME_RIGHT, y, Component.text(time).color(HINT), time, FONT, 18, Align.RIGHT);
    }

    // Kuerzt einen Namen mit Auslassungszeichen, wenn er nicht in die Spalte passt
    private String fit(Board board, String text, double width) {
        if (board.measure(text, FONT, 20) <= width) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && board.measure(cut + "…", FONT, 20) > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private void footer(Board board, Session s) {
        int page = page(s);
        int pages = pages(s);
        double pillX = -0.88;
        board.glyph(s, pillX, FOOTER_Y, "pille_210x52_31-58-89", null);
        String filter = "Quelle: " + filter(s);
        board.line(s, pillX, FOOTER_Y, Component.text(filter).color(TEXT), filter, FONT, 18, Align.CENTER);
        board.region(s, "p_filter", pillX, FOOTER_Y, 1.31, 0.325, "p_filter");
        button(board, s, 1.40, "Zurück", page > 0, "p_prev");
        String label = "Seite " + (page + 1) + "/" + pages;
        board.line(s, 2.20, FOOTER_Y, Component.text(label).color(HINT), label, FONT, 18, Align.CENTER);
        button(board, s, 3.00, "Weiter", page + 1 < pages, "p_next");
    }

    private void button(Board board, Session s, double x, String text, boolean enabled, String id) {
        board.glyph(s, x, FOOTER_Y, "knopf_100x44_normal_k", null);
        board.line(s, x, FOOTER_Y, Component.text(text).color(enabled ? ORANGE : HINT), text, FONT, 17, Align.CENTER);
        if (enabled) {
            board.region(s, id, x, FOOTER_Y, 0.625, 0.275, id);
        }
    }

    // ------------------------------------------------------------ Klicks

    @Override
    public boolean click(Board board, Session s, Element e) {
        if (!e.id().startsWith("p_") || !Boolean.TRUE.equals(s.data().get("team"))) {
            return false;
        }
        int page = page(s);
        String filter = filter(s);
        switch (e.id()) {
            case "p_prev" -> {
                if (page > 0) {
                    reload(board, s, page - 1, filter);
                }
            }
            case "p_next" -> {
                if (page + 1 < pages(s)) {
                    reload(board, s, page + 1, filter);
                }
            }
            case "p_filter" -> reload(board, s, 0, nextFilter(s, filter));
            default -> {
                return false;
            }
        }
        return true;
    }

    private String nextFilter(Session s, String current) {
        if (!(s.data().get(K_SOURCES) instanceof List<?> list) || list.isEmpty()) {
            return "Alle";
        }
        int index = list.indexOf(current);
        return String.valueOf(list.get((index + 1) % list.size()));
    }

    // Laedt im Hintergrund nach und zeichnet danach neu; waehrend des Ladens gelten weitere Klicks nicht
    private void reload(Board board, Session s, int page, String filter) {
        if (s.data().put(K_BUSY, true) != null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Result result = null;
            try {
                result = fetch(page, filter);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Protokoll nicht geladen: " + ex.getMessage());
            }
            Result done = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                s.data().remove(K_BUSY);
                if (done == null || board.session(s.player()) != s || !"protokoll".equals(s.page())) {
                    return;
                }
                s.data().put(K_PAGE, page);
                s.data().put(K_FILTER, filter);
                s.data().put(K_ROWS, done.rows());
                s.data().put(K_TOTAL, done.total());
                board.refresh(s);
            });
        });
    }

    // ------------------------------------------------------------ Werte der Sitzung

    @SuppressWarnings("unchecked")
    private static List<Row> rows(Session s) {
        return s.data().get(K_ROWS) instanceof List<?> list ? (List<Row>) list : List.of();
    }

    private static int page(Session s) {
        return s.data().get(K_PAGE) instanceof Number n ? n.intValue() : 0;
    }

    private static int pages(Session s) {
        long total = s.data().get(K_TOTAL) instanceof Number n ? n.longValue() : 0;
        return (int) Math.max(1, (total + ROWS - 1) / ROWS);
    }

    private static String filter(Session s) {
        return s.data().get(K_FILTER) instanceof String f ? f : "Alle";
    }

    // ------------------------------------------------------------ EthicEconomy (kann aelter und ohne Verlauf sein)

    /** Alle Zugriffe auf CoinHistoryApi stehen hier, damit eine aeltere EthicEconomy die Seite nicht zum Absturz bringt. */
    private static final class Source {

        record Line(UUID player, long amount, String source, Instant time) {
        }

        record Page(List<Line> lines, long total) {
        }

        /** Namen der Quellen, null wenn die Schnittstelle fehlt. */
        static List<String> sources(Plugin plugin) {
            try {
                var api = plugin.getServer().getServicesManager().load(de.ethicbuilds.economy.api.CoinHistoryApi.class);
                return api != null ? api.sources() : null;
            } catch (LinkageError e) {
                return null;
            }
        }

        static Page history(Plugin plugin, int offset, int limit, String source) {
            try {
                var api = plugin.getServer().getServicesManager().load(de.ethicbuilds.economy.api.CoinHistoryApi.class);
                if (api == null) {
                    return null;
                }
                var page = api.history(offset, limit, source);
                List<Line> lines = new ArrayList<>();
                for (var entry : page.entries()) {
                    lines.add(new Line(entry.player(), entry.amount(), entry.source(), entry.time()));
                }
                return new Page(lines, page.total());
            } catch (LinkageError e) {
                return null;
            }
        }
    }
}
