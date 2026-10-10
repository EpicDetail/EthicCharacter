package de.ethicbuilds.character.page.plot;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.page.PageHandler;
import de.ethicbuilds.character.page.Prompts;
import de.ethicbuilds.economy.format.CoinFormat;
import de.ethicbuilds.survival.api.PlotApi;
import de.ethicbuilds.survival.api.PlotApi.PlotView;
import de.ethicbuilds.survival.api.PlotApi.State;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seiten "plots" (mit eigenen Feldern oder auf einem fremden Feld) und "plots_leer" (keine Felder, Feld frei oder Spawn):
 * links die Liste der eigenen Felder, rechts die Karte des Feldes, auf dem der Spieler steht. Die Seite waehlt beim Zeichnen
 * selbst, welche der beiden Tafeln gilt. Zustandsvarianten der Pillen kommen als Glyphen "Seite/Element/Variante" aus
 * tools/tafel.js. Alle Daten liest die Seite beim Zeichnen aus der PlotApi (Server-Thread, nichts blockiert).
 */
public final class PlotsPage implements PageHandler {

    private static final String PREFIX = "§6Feld §8» §7";
    private static final String UI = "ethicbuilds:ui";
    private static final String BOLD = "ethicbuilds:ui_bold";
    private static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    private static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    private static final TextColor ORANGE = TextColor.fromHexString("#F9A11E");
    private static final TextColor TURQUOISE = TextColor.fromHexString("#3FE0E8");
    private static final TextColor GOLD = TextColor.fromHexString("#FFD24A");
    private static final TextColor RED = TextColor.fromHexString("#E05252");
    private static final TextColor LOCKED = TextColor.fromHexString("#6B7C90");
    private static final String COIN = "icon_muenze_20_255-210-74";
    private static final int MAX_ROWS = 6;
    private static final int MAX_TRUSTED = 5;
    private static final long CONFIRM_MS = 5000L;
    // Punkt links in der Status-Pille: der Text sitzt etwas weiter rechts
    private static final double DOT_SHIFT = 0.03;

    private static final String SNAP = "plSnap";
    private static final String OWN = "plOwn";
    private static final String CURRENT = "plCur";
    private static final String CONFIRM = "plConfirm";
    private static final Pattern ROW = Pattern.compile("^pl_zeile_(\\d)(_.*)?$");
    private static final Pattern TRUSTED = Pattern.compile("^pl_vertr_(\\d)(_.*)?$");

    /** Stand beim Zeichnen: eigene Felder, aktuelles Feld, Preis des naechsten, gueltige Tafel. */
    private record Snap(List<PlotView> own, PlotView current, long price, int max, String target) {
    }

    private record Confirm(int row, long until) {
    }

    private final Plugin plugin;
    private final Prompts prompts;
    private final PlotInfoPage info;

    public PlotsPage(Plugin plugin, Prompts prompts, PlotInfoPage info) {
        this.plugin = plugin;
        this.prompts = prompts;
        this.info = info;
    }

    @Override
    public Set<String> pages() {
        return Set.of("plots", "plots_leer");
    }

    private PlotApi api() {
        RegisteredServiceProvider<PlotApi> provider = plugin.getServer().getServicesManager().getRegistration(PlotApi.class);
        return provider != null ? provider.getProvider() : null;
    }

    // Persoenliche Grenze aus der Konfiguration von EthicSurvival (plot.personal-limit)
    private int personalLimit() {
        Plugin survival = Bukkit.getPluginManager().getPlugin("EthicSurvival");
        return survival != null ? Math.max(1, survival.getConfig().getInt("plot.personal-limit", 2)) : 2;
    }

    // Ein Stand je Zeichenvorgang: visible, content und draw sehen dieselben Werte, draw raeumt am Ende auf
    private Snap snap(Session s) {
        if (s.data().get(SNAP) instanceof Snap snap) {
            return snap;
        }
        PlotApi api = api();
        Player player = s.player();
        List<PlotView> own = api == null ? List.of() : api.own(player.getUniqueId());
        PlotView current = api == null ? null : api.current(player);
        long price = api == null ? -1 : api.nextPrice(player.getUniqueId());
        // Ohne eigene Felder gilt die leere Tafel, ausser man steht auf einem fremden Feld (dort braucht es die Infos)
        boolean empty = own.isEmpty() && (current == null || current.state() != State.FREMD);
        Snap snap = new Snap(own, current, price, personalLimit(), empty ? "plots_leer" : "plots");
        s.data().put(SNAP, snap);
        return snap;
    }

    // ------------------------------------------------------------ Sichtbarkeit und Texte

    @Override
    public boolean visible(Session s, Element e) {
        Snap sn = snap(s);
        if (!s.page().equals(sn.target())) {
            return false;
        }
        String id = e.id();
        PlotView cur = sn.current();
        switch (id) {
            case "pl_spawn_label", "pl_feld_status", "pl_leer" -> {
                return false;
            }
            case "pl_feld_koord" -> {
                return cur != null;
            }
            case "pl_feld_besitzer" -> {
                return cur != null && cur.state() != State.SPAWN;
            }
            case "pl_vertraute_label", "pl_vertraute_zahl" -> {
                return cur != null && cur.state() == State.EIGEN;
            }
            case "pl_hinzu" -> {
                return cur != null && cur.state() == State.EIGEN && cur.trusted().size() < MAX_TRUSTED;
            }
            case "pl_beanspruchen" -> {
                return cur != null && cur.state() == State.FREI && sn.price() >= 0;
            }
            case "pl_feld_infos" -> {
                return cur != null && cur.state() == State.FREMD;
            }
            default -> {
            }
        }
        Matcher row = ROW.matcher(id);
        if (row.matches()) {
            // Die Pille mit dem Zustand zeichnet draw als Variante
            return Integer.parseInt(row.group(1)) < Math.min(MAX_ROWS, sn.own().size()) && !id.endsWith("_zustand");
        }
        Matcher trusted = TRUSTED.matcher(id);
        if (trusted.matches()) {
            return cur != null && cur.state() == State.EIGEN && Integer.parseInt(trusted.group(1)) < Math.min(MAX_TRUSTED, cur.trusted().size());
        }
        return true;
    }

    @Override
    public Component content(Session s, Element e) {
        Snap sn = snap(s);
        PlotView cur = sn.current();
        String id = e.id();
        switch (id) {
            case "pl_oben_zahl" -> {
                return Component.text(sn.own().size() + " / " + sn.max());
            }
            case "pl_oben_hinweis" -> {
                return Component.empty();       // draw schreibt den Hinweis (mit Muenzsymbol)
            }
            case "pl_feld_koord" -> {
                return cur == null ? Component.empty() : Component.text("Feld " + cur.cellX() + ", " + cur.cellZ());
            }
            case "pl_feld_besitzer" -> {
                if (cur == null) {
                    return Component.empty();
                }
                return Component.text(cur.state() == State.FREI ? "Dieses Feld gehört noch niemandem." : "Besitzer: " + cur.ownerName());
            }
            case "pl_vertraute_zahl" -> {
                return Component.text((cur == null ? 0 : cur.trusted().size()) + " / " + MAX_TRUSTED);
            }
            default -> {
            }
        }
        Matcher row = ROW.matcher(id);
        if (row.matches() && row.group(2) != null) {
            int n = Integer.parseInt(row.group(1));
            if (n >= sn.own().size()) {
                return Component.empty();
            }
            PlotView view = sn.own().get(n);
            switch (row.group(2)) {
                case "_name" -> {
                    return Component.text("Feld " + view.cellX() + ", " + view.cellZ());
                }
                case "_frei" -> {
                    boolean armed = armed(s, n);
                    return red(armed ? "Sicher?" : "Freigeben");
                }
                default -> {
                }
            }
        }
        Matcher trusted = TRUSTED.matcher(id);
        if (trusted.matches() && trusted.group(2) != null && cur != null) {
            int n = Integer.parseInt(trusted.group(1));
            if (n >= cur.trusted().size()) {
                return Component.empty();
            }
            switch (trusted.group(2)) {
                case "_name" -> {
                    return Component.text(cur.trusted().get(n));
                }
                case "_x" -> {
                    return red("x");
                }
                default -> {
                }
            }
        }
        return null;
    }

    // Der Rahmen des Bretts faerbt Knopftext selbst; ein Unterelement behaelt seine Farbe
    private static Component red(String text) {
        return Component.text().append(Component.text(text).color(RED)).build();
    }

    private static boolean armed(Session s, int row) {
        return s.data().get(CONFIRM) instanceof Confirm c && c.row() == row && System.currentTimeMillis() < c.until();
    }

    // ------------------------------------------------------------ Zeichnen

    @Override
    public void draw(Board board, Session s, String page) {
        Snap sn = snap(s);
        if (!page.equals(sn.target())) {
            board.show(s, sn.target());     // zeichnet mit demselben Stand die richtige Tafel
            return;
        }
        s.data().put(OWN, sn.own());
        s.data().put(CURRENT, sn.current());
        try {
            note(board, s, page, sn);
            rows(board, s, page, sn);
            if (sn.own().isEmpty()) {
                empty(board, s);
            }
            field(board, s, page, sn);
        } finally {
            s.data().remove(SNAP);
        }
    }

    // Hinweis oben: naechstes Feld gratis, mit Preis oder nur mit Organisation
    private void note(Board board, Session s, String page, Snap sn) {
        Element pill = element(board, page, "pl_oben_hinweis");
        if (pill == null) {
            return;
        }
        int px = pill.px();
        double y = pill.y();
        if (sn.price() < 0) {
            board.line(s, pill.x(), y, Component.text("Dafür brauchst du eine Organisation mit 2 Spielern").color(TEXT),
                    "Dafür brauchst du eine Organisation mit 2 Spielern", UI, px, Align.CENTER);
        } else if (sn.price() == 0) {
            board.line(s, pill.x(), y, Component.text("Nächstes Feld: gratis").color(TEXT), "Nächstes Feld: gratis", UI, px, Align.CENTER);
        } else {
            String label = "Nächstes Feld: ";
            String price = CoinFormat.number(sn.price());
            double total = board.measure(label, UI, px) + 0.125 + 0.05 + board.measure(price, BOLD, px);
            double left = pill.x() - total / 2;
            board.line(s, left, y, Component.text(label).color(TEXT), label, UI, px, Align.LEFT);
            double coin = left + board.measure(label, UI, px) + 0.0625;
            board.glyph(s, coin, y, COIN, null);
            board.line(s, coin + 0.0625 + 0.05, y, Component.text(price).color(GOLD), price, BOLD, px, Align.LEFT);
        }
    }

    // Zustandspillen der Zeilen: "Deins" (Organisationsfelder gibt es noch nicht)
    private void rows(Board board, Session s, String page, Snap sn) {
        for (int i = 0; i < Math.min(MAX_ROWS, sn.own().size()); i++) {
            Element pill = element(board, page, "pl_zeile_" + i + "_zustand");
            if (pill != null) {
                board.glyph(s, pill.x(), pill.y(), page + "/pl_zeile_zustand/deins", null);
                board.line(s, pill.x(), pill.y(), Component.text("Deins").color(ORANGE), "Deins", UI, pill.px(), Align.CENTER);
            }
        }
    }

    // Leerzustand der Liste: Pin, Titel und Hinweis in mehreren Zeilen
    private void empty(Board board, Session s) {
        Element e = element(board, "plots_leer", "pl_leer");
        if (e == null) {
            return;
        }
        board.glyph(s, e.x(), e.y() + 0.36, e.extra("iconBild", ""), null);
        board.line(s, e.x(), e.y() - 0.02, Component.text(e.text()).color(TEXT), e.text(), BOLD, e.px(), Align.CENTER);
        String sub = e.extra("untertext", "");
        double y = e.y() - 0.28;
        for (String line : board.wrap(sub, UI, 17, 2.4)) {
            board.line(s, e.x(), y, Component.text(line).color(HINT), line, UI, 17, Align.CENTER);
            y -= 0.17;
        }
    }

    // Rechte Karte: Status-Pille, bei freiem Feld Preis und Knopf, bei gesperrtem Beanspruchen der Grund
    private void field(Board board, Session s, String page, Snap sn) {
        Element card = element(board, page, "pl_feld");
        PlotView cur = sn.current();
        if (card == null) {
            return;
        }
        if (cur == null) {
            board.line(s, card.x(), card.y(), Component.text("Hier gibt es keine Felder.").color(HINT), "Hier gibt es keine Felder.", UI, 17, Align.CENTER);
            return;
        }
        Element status = element(board, page, "pl_feld_status");
        if (status != null) {
            String variant = switch (cur.state()) {
                case FREI -> "frei";
                case SPAWN -> "spawn";
                default -> "beansprucht";
            };
            String text = switch (cur.state()) {
                case FREI -> "Frei";
                case SPAWN -> "Spawn";
                default -> "Beansprucht";
            };
            TextColor color = switch (cur.state()) {
                case FREI -> TURQUOISE;
                case SPAWN -> HINT;
                default -> ORANGE;
            };
            board.glyph(s, status.x(), status.y(), page + "/pl_feld_status/" + variant, null);
            double shift = cur.state() == State.SPAWN ? 0 : DOT_SHIFT;
            board.line(s, status.x() + shift, status.y(), Component.text(text).color(color), text, BOLD, status.px(), Align.CENTER);
        }
        Element claim = element(board, page, "pl_beanspruchen");
        if (claim == null) {
            return;
        }
        boolean spawn = cur.state() == State.SPAWN;
        boolean needsOrganisation = cur.state() == State.FREI && sn.price() < 0;
        if (spawn || needsOrganisation) {
            // gesperrter Knopf mit Grund darunter
            board.glyph(s, claim.x(), claim.y(), page + "/pl_beanspruchen/gesperrt", null);
            board.line(s, claim.x(), claim.y(), Component.text("Beanspruchen").color(LOCKED), "Beanspruchen", UI, claim.px(), Align.CENTER);
            hint(board, s, claim, spawn ? "Das Spawn-Feld kann nicht beansprucht werden." : "Dafür brauchst du eine Organisation mit 2 Spielern.");
        } else if (cur.state() == State.FREI) {
            double y = claim.y() - claim.height() / 2 - 0.13;
            if (sn.price() == 0) {
                board.line(s, claim.x(), y, Component.text("Dein erstes Feld ist gratis").color(HINT), "Dein erstes Feld ist gratis", UI, 16, Align.CENTER);
            } else {
                String label = "Kostet ";
                String price = CoinFormat.number(sn.price());
                double total = board.measure(label, UI, 16) + 0.125 + 0.05 + board.measure(price, BOLD, 16);
                double left = claim.x() - total / 2;
                board.line(s, left, y, Component.text(label).color(HINT), label, UI, 16, Align.LEFT);
                double coin = left + board.measure(label, UI, 16) + 0.0625;
                board.glyph(s, coin, y, COIN, null);
                board.line(s, coin + 0.0625 + 0.05, y, Component.text(price).color(GOLD), price, BOLD, 16, Align.LEFT);
            }
        }
    }

    // Grauer Hinweis unter dem Knopf, bricht in der Breite der Karte um
    private void hint(Board board, Session s, Element button, String text) {
        double y = button.y() - button.height() / 2 - 0.13;
        for (String line : board.wrap(text, UI, 16, 1.7)) {
            board.line(s, button.x(), y, Component.text(line).color(HINT), line, UI, 16, Align.CENTER);
            y -= 0.16;
        }
    }

    // ------------------------------------------------------------ Klicks

    @Override
    public boolean click(Board board, Session s, Element e) {
        PlotApi api = api();
        Player player = s.player();
        String id = e.id();
        if (api == null) {
            return false;
        }
        List<PlotView> own = ownFields(s);
        PlotView cur = s.data().get(CURRENT) instanceof PlotView v ? v : null;
        Matcher row = ROW.matcher(id);
        if (row.matches() && row.group(2) != null) {
            int n = Integer.parseInt(row.group(1));
            if (n >= own.size()) {
                return true;
            }
            PlotView view = own.get(n);
            switch (row.group(2)) {
                case "_hin" -> {
                    if (api.teleport(player, view.cellX(), view.cellZ())) {
                        board.close(player);
                    }
                }
                case "_frei" -> release(board, s, api, view, n);
                default -> {
                }
            }
            return true;
        }
        Matcher trusted = TRUSTED.matcher(id);
        if (trusted.matches() && trusted.group(2) != null) {
            int n = Integer.parseInt(trusted.group(1));
            if (trusted.group(2).equals("_x") && cur != null && n < cur.trusted().size()) {
                report(player, api.untrust(player, cur.trusted().get(n)));
                refresh(board, s);
            }
            return true;
        }
        switch (id) {
            case "pl_beanspruchen" -> {
                report(player, api.claim(player));
                refresh(board, s);
            }
            case "pl_hinzu" -> prompts.ask(player, "Wen willst du vertrauen?", name -> {
                report(player, api.trust(player, name));
                refresh(board, s);
            });
            case "pl_feld_infos" -> {
                if (cur != null && cur.state() == State.FREMD) {
                    info.show(board, s, cur.cellX(), cur.cellZ());
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // Freigeben mit Rueckfrage: erster Klick schaltet die Zeile fuer 5 Sekunden auf "Sicher?", der zweite gibt frei
    private void release(Board board, Session s, PlotApi api, PlotView view, int row) {
        if (armed(s, row)) {
            s.data().remove(CONFIRM);
            report(s.player(), api.abandon(s.player(), view.cellX(), view.cellZ()));
            refresh(board, s);
            return;
        }
        Confirm confirm = new Confirm(row, System.currentTimeMillis() + CONFIRM_MS);
        s.data().put(CONFIRM, confirm);
        refresh(board, s);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (s.data().get(CONFIRM) == confirm) {
                s.data().remove(CONFIRM);
                refresh(board, s);
            }
        }, CONFIRM_MS / 50 + 2);
    }

    @SuppressWarnings("unchecked")
    private static List<PlotView> ownFields(Session s) {
        return s.data().get(OWN) instanceof List<?> list ? (List<PlotView>) list : List.of();
    }

    private static void report(Player player, PlotApi.Result result) {
        player.sendMessage(PREFIX + (result.ok() ? "§a" : "§c") + result.message());
    }

    // Nur neu zeichnen, wenn die Tafel noch offen ist und eine Plots-Seite zeigt
    private static void refresh(Board board, Session s) {
        if (board.session(s.player()) == s && ("plots".equals(s.page()) || "plots_leer".equals(s.page()))) {
            board.refresh(s);
        }
    }

    private static Element element(Board board, String page, String id) {
        for (Element e : board.layout().page(page)) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }
}
