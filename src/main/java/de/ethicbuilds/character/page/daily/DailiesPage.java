package de.ethicbuilds.character.page.daily;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.page.PageHandler;
import de.ethicbuilds.economy.format.CoinFormat;
import de.ethicbuilds.survival.api.DailyApi;
import de.ethicbuilds.survival.api.DailyApi.DailyView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seite "dailies": drei Karten mit Icon, Titel, Beschreibung, Fortschritt, Belohnung und Status. Karten, Titel und
 * Reset-Pille kommen aus dem Layout, die Inhalte der Karten zeichnet diese Klasse relativ zur Karte.
 */
public final class DailiesPage implements PageHandler {

    private static final String UI = "ethicbuilds:ui";
    private static final String BOLD = "ethicbuilds:ui_bold";
    private static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    private static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    private static final TextColor GOLD = TextColor.fromHexString("#FFD24A");
    private static final TextColor GREEN = TextColor.fromHexString("#2DBE6C");
    private static final TextColor ORANGE = TextColor.fromHexString("#F9A11E");
    private static final Material[] ICONS = {Material.IRON_SWORD, Material.DIAMOND_PICKAXE, Material.BRICKS};

    private final Plugin plugin;

    public DailiesPage(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Set<String> pages() {
        return Set.of("dailies");
    }

    /** Aufgaben und Restzeit laden (nicht im Hauptthread, die Schnittstelle darf die Datenbank lesen). */
    @Override
    public void load(Player player, Map<String, Object> data, String page) {
        RegisteredServiceProvider<DailyApi> provider = plugin.getServer().getServicesManager().getRegistration(DailyApi.class);
        if (provider == null) {
            data.put("dailies", List.of());
            data.put("dailiesDone", 0);
            return;
        }
        DailyApi api = provider.getProvider();
        List<DailyView> views = api.dailies(player.getUniqueId());
        data.put("dailies", views);
        data.put("dailiesDone", (int) views.stream().filter(DailyView::done).count());
        data.put("resetHours", Math.max(1, (api.secondsUntilReset() + 3599) / 3600));
    }

    @Override
    public void draw(Board board, Session s, String page) {
        List<DailyView> views = views(s);
        if (views.isEmpty()) {
            board.text(s, 0.887, -0.2, Component.text("Die Tagesaufgaben sind gerade nicht verfügbar.").color(HINT)
                    .font(net.kyori.adventure.key.Key.key(UI)), 22);
            return;
        }
        for (int i = 0; i < views.size() && i < 3; i++) {
            Element card = element(board, page, "d_karte_" + i);
            if (card != null) {
                card(board, s, card, views.get(i), i);
            }
        }
    }

    private void card(Board board, Session s, Element card, DailyView view, int index) {
        double left = card.x() - card.width() / 2;
        double y = card.y();
        double right = card.x() + card.width() / 2;
        // Icon links (68 px = 0,425 Bloecke): Flaeche und Gegenstand darauf
        double iconX = left + 0.075 + 0.2;
        board.glyph(s, iconX, y, "flaeche_a_icon", null);
        board.item(s, iconX, y, 0.27, new ItemStack(ICONS[index % ICONS.length]));
        double textX = iconX + 0.2 + 0.15;
        board.line(s, textX, y + 0.2, Component.text(view.name()).color(TEXT), view.name(), BOLD, 24, Align.LEFT);
        // Beschreibung: nur die erste Zeile, rechts bleiben Belohnung und Status frei
        List<String> lines = board.wrap(view.description(), UI, 17, 2.7);
        if (!lines.isEmpty()) {
            board.line(s, textX, y - 0.02, Component.text(lines.get(0)).color(HINT), lines.get(0), UI, 17, Align.LEFT);
        }
        // Fortschritt: schmale Linie und Zahl
        TextColor accent = view.done() ? GREEN : ORANGE;
        Element track = Element.region("d_spur_" + index, textX + 0.9, y - 0.26, 1.8, 0.04, "");
        board.bar(s, track, view.goal() > 0 ? (double) view.progress() / view.goal() : 1, view.done() ? "gruen" : "orange");
        String count = CoinFormat.number(view.progress()) + " / " + CoinFormat.number(view.goal());
        board.line(s, textX + 1.9, y - 0.26, Component.text(count).color(view.done() ? GREEN : TEXT), count, UI, 17, Align.LEFT);

        // Status-Pille rechts, davor die Belohnung
        double statusX = right - 0.12 - 0.4065;
        board.glyph(s, statusX, y, "pille_130x40_31-58-89", null);
        String status = view.done() ? "Erledigt" : "Offen";
        board.line(s, statusX, y, Component.text(status).color(accent), status, BOLD, 17, Align.CENTER);
        double rewardX = statusX - 0.4065 - 0.08 - 0.3125;
        board.glyph(s, rewardX, y, "pille_100x40_31-58-89", null);
        String reward = CoinFormat.number(view.reward());
        double total = 0.125 + 0.05 + board.measure(reward, BOLD, 17);
        double groupLeft = rewardX - total / 2;
        board.glyph(s, groupLeft + 0.0625, y, "icon_muenze_20_255-210-74", null);
        board.line(s, groupLeft + 0.175, y, Component.text(reward).color(GOLD), reward, BOLD, 17, Align.LEFT);
    }

    @Override
    public boolean click(Board board, Session session, Element element) {
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<DailyView> views(Session s) {
        return s.data().get("dailies") instanceof List<?> list ? (List<DailyView>) list : List.of();
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
