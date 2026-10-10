package de.ethicbuilds.character.page.plot;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.page.PageHandler;
import de.ethicbuilds.survival.api.PlotApi;
import de.ethicbuilds.survival.api.PlotApi.PlotView;
import de.ethicbuilds.survival.api.PlotApi.State;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seite "plot_info" (Tafel 6 x 3,5): Kopf mit Emblem, Name, Feld und Anfuehrer, vier Zahlenkarten und die Karte "Angriffsziel".
 * Organisationen, Staedte und Prestige gibt es noch nicht: ohne Organisation zeigt die Seite den Besitzer als Einzelspieler,
 * die Prestige-Pille entfaellt. Das Feld steht in session.data() (piX, piZ).
 */
public final class PlotInfoPage implements PageHandler {

    private static final String UI = "ethicbuilds:ui";
    private static final String BOLD = "ethicbuilds:ui_bold";
    private static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    private static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    private static final TextColor GREEN = TextColor.fromHexString("#2DBE6C");
    private static final TextColor RED = TextColor.fromHexString("#E05252");
    private static final String PAGE = "plot_info";
    private static final String CELL_X = "piX";
    private static final String CELL_Z = "piZ";
    private static final String VIEW = "piView";
    private static final String ATTACK_TEXT = "Angriffe sind nur im Kriegsfenster möglich. Die Regeln folgen.";
    // Noch kein Krieg: jedes Feld ist geschuetzt
    private static final boolean ATTACKABLE = false;

    private final Plugin plugin;
    // Feld, das der naechste Oeffnen-Vorgang eines Spielers zeigt (Befehl /charakter plotinfo)
    private final Map<UUID, int[]> requested = new ConcurrentHashMap<>();

    public PlotInfoPage(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Set<String> pages() {
        return Set.of(PAGE);
    }

    /** Merkt das Feld fuer den Spieler, bevor die Tafel mit dieser Seite geoeffnet wird. */
    public void request(Player player, int cellX, int cellZ) {
        requested.put(player.getUniqueId(), new int[]{cellX, cellZ});
    }

    /** Zeigt die Info-Tafel fuer ein Feld in einer schon offenen Tafel. */
    public void show(Board board, Session s, int cellX, int cellZ) {
        s.data().put(CELL_X, cellX);
        s.data().put(CELL_Z, cellZ);
        board.show(s, PAGE);
    }

    @Override
    public void load(Player player, Map<String, Object> data, String page) {
        int[] cell = requested.remove(player.getUniqueId());
        if (cell != null) {
            data.put(CELL_X, cell[0]);
            data.put(CELL_Z, cell[1]);
        }
    }

    private PlotApi api() {
        RegisteredServiceProvider<PlotApi> provider = plugin.getServer().getServicesManager().getRegistration(PlotApi.class);
        return provider != null ? provider.getProvider() : null;
    }

    /** Das gezeigte Feld; null, wenn keines gewaehlt ist oder es nicht (mehr) beansprucht ist. Ein Stand je Zeichenvorgang. */
    private PlotView view(Session s) {
        if (s.data().containsKey(VIEW)) {
            return s.data().get(VIEW) instanceof PlotView v ? v : null;
        }
        PlotApi api = api();
        PlotView view = null;
        if (api != null && s.data().get(CELL_X) instanceof Integer x && s.data().get(CELL_Z) instanceof Integer z) {
            PlotView found = api.at(s.player().getWorld(), x, z);
            if (found != null && (found.state() == State.FREMD || found.state() == State.EIGEN)) {
                view = found;
            }
        }
        // null kann nicht in die Map: ein Platzhalter steht fuer "kein Feld"
        s.data().put(VIEW, view != null ? view : Boolean.FALSE);
        return view;
    }

    // Organisationen kommen spaeter: bis dahin ist der Besitzer Name und Anfuehrer
    private static String ownerName(PlotView v) {
        return v.ownerName();
    }

    private static String initial(PlotView v) {
        String name = ownerName(v);
        return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private long fieldCount(PlotView v) {
        PlotApi api = api();
        Player online = Bukkit.getPlayerExact(v.ownerName());
        OfflinePlayer owner = online != null ? online : Bukkit.getOfflinePlayerIfCached(v.ownerName());
        return api != null && owner != null ? api.count(owner.getUniqueId()) : -1;
    }

    // ------------------------------------------------------------ Sichtbarkeit und Texte

    @Override
    public boolean visible(Session s, Element e) {
        PlotView v = view(s);
        return switch (e.id()) {
            case "tafel", "pi_schliessen" -> true;
            case "pi_prestige" -> false;                        // ohne Stadt keine Prestige-Pille
            case "pi_angriff" -> v != null && ATTACKABLE;       // geschuetzt: gruene Variante zeichnet draw
            default -> v != null;
        };
    }

    @Override
    public Component content(Session s, Element e) {
        PlotView v = view(s);
        if (v == null) {
            return Component.empty();
        }
        return switch (e.id()) {
            case "pi_emblem" -> Component.text(initial(v));
            case "pi_name" -> Component.text(ownerName(v));
            case "pi_sub" -> Component.text("Feld " + v.cellX() + ", " + v.cellZ() + " · Anführer: " + ownerName(v));
            default -> null;
        };
    }

    // ------------------------------------------------------------ Zeichnen

    @Override
    public void draw(Board board, Session s, String page) {
        PlotView v = view(s);
        try {
            if (v == null) {
                board.text(s, 0, 0, Component.text("Dieses Feld ist nicht beansprucht.").color(HINT).font(Key.key(UI)), 22);
                return;
            }
            stats(board, s, page, v);
            attack(board, s, page);
        } finally {
            s.data().remove(VIEW);
        }
    }

    // Vier Karten: Label, grosse Zahl, Untertext
    private void stats(Board board, Session s, String page, PlotView v) {
        long fields = fieldCount(v);
        String[][] cards = {
                {"Spieler", "1", "Einzelspieler"},
                {"Organisationen", "-", "noch keine"},
                {"Felder", fields < 0 ? "-" : String.valueOf(fields), "dieses Besitzers"},
                {"Prestige", "-", "ohne Stadt"},
        };
        for (int i = 0; i < cards.length; i++) {
            Element card = element(board, page, "pi_stat_" + i);
            if (card == null) {
                continue;
            }
            double left = card.x() - card.width() / 2 + 0.14;
            board.line(s, left, card.y() + 0.24, Component.text(cards[i][0]).color(HINT), cards[i][0], UI, 15, Align.LEFT);
            board.line(s, left, card.y() - 0.02, Component.text(cards[i][1]).color(TEXT), cards[i][1], BOLD, 40, Align.LEFT);
            board.line(s, left, card.y() - 0.27, Component.text(cards[i][2]).color(HINT), cards[i][2], UI, 14, Align.LEFT);
        }
    }

    // Angriffsziel: Titel, Status-Pille rechts, Text darunter. Geschuetzt = gruener Rand, Angreifbar = roter Rand (Layout)
    private void attack(Board board, Session s, String page) {
        Element card = element(board, page, "pi_angriff");
        if (card == null) {
            return;
        }
        if (!ATTACKABLE) {
            board.glyph(s, card.x(), card.y(), page + "/pi_angriff/gruen", null);
        }
        double left = card.x() - card.width() / 2 + 0.3;
        double top = card.y() + card.height() / 2;
        board.line(s, left, top - 0.3, Component.text("Angriffsziel").color(TEXT), "Angriffsziel", BOLD, 24, Align.LEFT);
        String status = ATTACKABLE ? "Angreifbar" : "Geschützt";
        TextColor color = ATTACKABLE ? RED : GREEN;
        double pillX = card.x() + card.width() / 2 - 0.3 - 0.425;
        board.glyph(s, pillX, top - 0.3, page + "/pi_angriff/" + (ATTACKABLE ? "status_rot" : "status_gruen"), null);
        board.line(s, pillX, top - 0.3, Component.text(status).color(color), status, BOLD, 16, Align.CENTER);
        double y = top - 0.72;
        for (String line : board.wrap(ATTACK_TEXT, UI, 17, card.width() - 0.6)) {
            board.line(s, left, y, Component.text(line).color(HINT), line, UI, 17, Align.LEFT);
            y -= 0.1625;
        }
    }

    @Override
    public boolean click(Board board, Session session, Element element) {
        if (element.id().equals("pi_schliessen")) {
            board.close(session.player());
            return true;
        }
        return false;
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
