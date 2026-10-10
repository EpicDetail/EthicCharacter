package de.ethicbuilds.character.page;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.economy.format.CoinFormat;
import de.ethicbuilds.survival.api.AuctionApi;
import de.ethicbuilds.survival.api.LevelApi;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/** Inhalt und Klicks der Charakter-Tafel. Texte und Linien stehen mit ihrer Lage im Layout. */
public final class CharacterPages implements Board.Pages {

    private static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    private static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    private static final TextColor ORANGE = TextColor.fromHexString("#F9A11E");
    private static final TextColor GOLD = TextColor.fromHexString("#FFD24A");
    private static final Key FONT = Key.key("ethicbuilds:ui");
    private static final Key BOLD = Key.key("ethicbuilds:ui_bold");
    private static final Key HUD = Key.key("ethicbuilds:hud");

    private final Plugin plugin;

    public CharacterPages(Plugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ Texte der Layout-Elemente

    @Override
    public Component content(Session s, Element e) {
        if (e.text().isEmpty()) {
            return null;
        }
        boolean max = number(s, "level") >= LevelApi.MAX_LEVEL;
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
                return Component.text().append(Component.text("").font(HUD).color(TextColor.color(0xFFFFFF)))
                        .append(Component.text(" " + fill(s, e.text())).color(GOLD).font(BOLD)).build();
            }
            case "in_level_fuss" -> {
                if (max) {
                    return Component.text("Höchstes Level erreicht");
                }
            }
            case "in_xp_fuss" -> {
                if (max) {
                    return Component.text("Maximum erreicht");
                }
            }
            default -> {
            }
        }
        return Component.text(fill(s, e.text()));
    }

    // Platzhalter aus den Werten der Tafel, Zahlen mit Tausenderpunkt
    private String fill(Session s, String text) {
        long level = number(s, "level");
        long into = number(s, "xpInto");
        long next = number(s, "xpNext");
        long percent = next > 0 ? Math.round(100.0 * into / next) : 100;
        return text.replace("{name}", string(s, "name")).replace("{level}", String.valueOf(level))
                .replace("{rang}", string(s, "rank")).replace("{coins}", CoinFormat.number(number(s, "coins")))
                .replace("{xp}", CoinFormat.number(number(s, "xp"))).replace("{xpIntoLevel}", CoinFormat.number(into))
                .replace("{xpForNextLevel}", CoinFormat.number(next)).replace("{prozent}", String.valueOf(percent))
                .replace("{n}", String.valueOf(level + 1)).replace("{erledigt}", String.valueOf(number(s, "dailiesDone")))
                .replace("{h}", String.valueOf(number(s, "resetHours")))
                .replace("{steuer}", String.format(Locale.GERMANY, "%.1f", (Double) s.data().getOrDefault("tax", 0.0)))
                .replace("{stufe}", String.valueOf(Math.min(9, level / 10)));
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

    // ------------------------------------------------------------ Linien und Seiten

    @Override
    public void draw(Board board, Session s, String page) {
        boolean max = number(s, "level") >= LevelApi.MAX_LEVEL;
        for (Element e : board.layout().page(page)) {
            if (e.type().equals("linie") && !e.fill().isEmpty()) {
                board.bar(s, e, progress(s, e.fill()), max && e.id().startsWith("in_level") ? "gold" : "orange");
            }
        }
        if (!page.equals("uebersicht") && !page.equals("verzauberer")) {
            board.text(s, 0.9, -0.2, Component.text("Diese Seite kommt als Nächstes.").color(HINT).font(FONT), 22);
        }
    }

    // "xpIntoLevel/xpForNextLevel" oder "erledigt/3": Zaehler durch Nenner, ohne Nenner voll
    private double progress(Session s, String expression) {
        String[] parts = expression.split("/");
        if (parts.length != 2) {
            return 0;
        }
        double numerator = value(s, parts[0].trim());
        double denominator = value(s, parts[1].trim());
        return denominator > 0 ? Math.max(0, Math.min(1, numerator / denominator)) : 1.0;
    }

    private double value(Session s, String token) {
        return switch (token) {
            case "xpIntoLevel" -> number(s, "xpInto");
            case "xpForNextLevel" -> number(s, "xpNext");
            case "erledigt" -> number(s, "dailiesDone");
            default -> {
                try {
                    yield Double.parseDouble(token);
                } catch (NumberFormatException ex) {
                    yield number(s, token);
                }
            }
        };
    }

    // ------------------------------------------------------------ Klicks

    @Override
    public void click(Board board, Session s, Element e) {
        Player player = s.player();
        switch (e.action()) {
            case "uebersicht", "dailies", "protokoll", "ueberweisen" -> open(board, s, e.action());
            case "auktion", "AuctionApi.open(Player)" -> {
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
