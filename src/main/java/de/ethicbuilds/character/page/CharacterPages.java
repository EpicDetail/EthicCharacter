package de.ethicbuilds.character.page;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.economy.format.CoinFormat;
import de.ethicbuilds.survival.api.AuctionApi;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/** Inhalt und Klicks der Charakter-Tafel. */
public final class CharacterPages implements Board.Pages {

    private static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    private static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    private static final TextColor ORANGE = TextColor.fromHexString("#F9A11E");
    private static final TextColor GOLD = TextColor.fromHexString("#FFD24A");
    private static final Key FONT = Key.key("ethicbuilds:ui");
    private static final Key BOLD = Key.key("ethicbuilds:ui_bold");

    private final Plugin plugin;
    private Board board;

    public CharacterPages(Plugin plugin) {
        this.plugin = plugin;
    }

    public void attach(Board board) {
        this.board = board;
    }

    // ------------------------------------------------------------ Texte der Layout-Elemente

    @Override
    public Component content(Session s, Element e) {
        if (e.text().isEmpty()) {
            return null;
        }
        switch (e.id()) {
            case "kopf_name" -> {
                // Name, Punkt und Level in einer Zeile, damit sie dicht beieinander stehen
                return Component.text(string(s, "name")).color(TEXT).font(BOLD)
                        .append(Component.text("  ·  ").color(HINT).font(BOLD))
                        .append(Component.text("Level " + number(s, "level")).color(ORANGE).font(BOLD));
            }
            case "kopf_level" -> {
                return null;
            }
            case "kopf_coins" -> {
                return Component.text().append(Component.text("").font(Key.key("ethicbuilds:hud")).color(TextColor.color(0xFFFFFF)))
                        .append(Component.text(" " + CoinFormat.number(number(s, "coins")) + " EthicCoins").color(GOLD).font(BOLD)).build();
            }
            default -> {
                return Component.text(fill(s, e.text()));
            }
        }
    }

    private String fill(Session s, String text) {
        return text.replace("{name}", string(s, "name")).replace("{level}", String.valueOf(number(s, "level")))
                .replace("{rang}", string(s, "rank")).replace("{coins}", CoinFormat.number(number(s, "coins")))
                .replace("{h}", String.valueOf(number(s, "resetHours")));
    }

    @Override
    public boolean visible(Session s, Element e) {
        return !e.visibleFor().equals("team") || Boolean.TRUE.equals(s.data().get("team"));
    }

    @Override
    public TextColor[] gradient(Session s) {
        return new TextColor[]{color(string(s, "color1")), color(string(s, "color2"))};
    }

    private static TextColor color(String hex) {
        TextColor c = hex != null && hex.startsWith("#") ? TextColor.fromHexString(hex) : null;
        return c != null ? c : ORANGE;
    }

    // ------------------------------------------------------------ Karten und Seiten

    @Override
    public void draw(Board board, Session s, String page) {
        switch (page) {
            case "uebersicht" -> overview(board, s);
            default -> comingSoon(board, s, page);
        }
    }

    private void overview(Board board, Session s) {
        long level = number(s, "level");
        long into = number(s, "xpInto");
        long next = number(s, "xpNext");
        double progress = next > 0 ? (double) into / next : 1.0;
        boolean max = next == 0;

        Element card = element(board, s, "karte_level");
        title(board, s, card, "Level");
        big(board, s, card, String.valueOf(level), max ? GOLD : ORANGE);
        board.bar(s, element(board, s, "karte_level_linie"), progress, max ? "gold" : "orange");
        small(board, s, card, -0.42, max ? "Höchstes Level" : Math.round(progress * 100) + " % bis Level " + (level + 1), HINT);

        card = element(board, s, "karte_xp");
        title(board, s, card, "XP gesamt");
        big(board, s, card, CoinFormat.number(number(s, "xp")), TEXT);
        small(board, s, card, -0.12, "XP bis nächstes Level", HINT);
        small(board, s, card, -0.45, max ? "Maximum erreicht" : CoinFormat.number(into) + " / " + CoinFormat.number(next) + " XP", ORANGE);

        card = element(board, s, "karte_kontostand");
        title(board, s, card, "Kontostand");
        big(board, s, card, CoinFormat.number(number(s, "coins")), GOLD);

        card = element(board, s, "karte_dailies");
        long done = number(s, "dailiesDone");
        title(board, s, card, "Dailies heute");
        big(board, s, card, done + " / " + CharacterData.DAILIES_PER_DAY, TEXT);
        board.bar(s, element(board, s, "karte_dailies_linie"), (double) done / CharacterData.DAILIES_PER_DAY, "orange");
        small(board, s, card, -0.35, "wieder da in " + number(s, "resetHours") + " Std.", HINT);

        card = element(board, s, "karte_steuer");
        title(board, s, card, "Überweisungssteuer");
        big(board, s, card, String.format(Locale.GERMANY, "%.1f %%", (Double) s.data().getOrDefault("tax", 0.0)), ORANGE);
        small(board, s, card, -0.3, "Ändert sich mit dem\nGeld im Umlauf", HINT);

        card = element(board, s, "karte_auktion");
        title(board, s, card, "Auktionshaus");
        small(board, s, card, 0.1, "Kaufen und verkaufen\nbeim Auktionator", HINT);
    }

    private void comingSoon(Board board, Session s, String page) {
        board.text(s, 0.9, 0.0, Component.text("Diese Seite kommt als Nächstes.").color(HINT).font(FONT), 22);
    }

    private Element element(Board board, Session s, String id) {
        return board.layout().page(s.page()).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    private void title(Board board, Session s, Element card, String text) {
        board.text(s, card.x(), card.y() + card.height() / 2 - 0.25, Component.text(text).color(HINT).font(BOLD), 19);
    }

    // Grosse Zahl der Karte, die Schrift wird kleiner, wenn der Text sonst nicht passt
    private void big(Board board, Session s, Element card, String text, TextColor color) {
        double available = card.width() * 160 * 0.86;
        int px = (int) Math.min(52, available / (Math.max(1, text.length()) * 0.6));
        board.text(s, card.x(), card.y() + 0.28, Component.text(text).color(color).font(BOLD), Math.max(20, px));
    }

    private void small(Board board, Session s, Element card, double offset, String text, TextColor color) {
        board.text(s, card.x(), card.y() + offset, Component.text(text).color(color).font(FONT), 17);
    }

    // ------------------------------------------------------------ Klicks

    @Override
    public void click(Board board, Session s, Element e) {
        Player player = s.player();
        switch (e.action()) {
            case "uebersicht", "dailies", "protokoll" -> open(board, s, e.action());
            case "ueberweisen" -> open(board, s, "ueberweisen");
            case "auktion" -> {
                board.close(player);
                AuctionApi auction = Bukkit.getServicesManager().load(AuctionApi.class);
                if (auction != null) {
                    auction.open(player);
                }
            }
            case "schliessen" -> board.close(player);
            default -> {
            }
        }
    }

    private void open(Board board, Session s, String page) {
        if (board.layout().hasPage(page) && !page.equals(s.page())) {
            board.show(s, page);
        }
    }

    private static long number(Session s, String key) {
        Object value = s.data().get(key);
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static String string(Session s, String key) {
        Object value = s.data().get(key);
        return value != null ? value.toString() : "";
    }
}
