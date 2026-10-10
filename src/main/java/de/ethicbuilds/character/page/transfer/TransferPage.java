package de.ethicbuilds.character.page.transfer;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.page.CharacterData;
import de.ethicbuilds.character.page.PageHandler;
import de.ethicbuilds.character.page.Prompts;
import de.ethicbuilds.economy.format.CoinFormat;
import de.ethicbuilds.survival.api.AuctionApi;
import de.ethicbuilds.survival.api.TransferApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Seite "ueberweisen": links die Formularkarte (Empfaenger, Betrag), rechts die Summenkarte mit Steuer und dem Knopf zum
 * Ueberweisen. Die Karten kommen aus dem Layout, alles darin zeichnet diese Klasse. Der Zustand der Eingabe liegt in
 * session.data() (uTo, uToName, uAmount, uBusy).
 */
public final class TransferPage implements PageHandler {

    private static final String PREFIX = "§6Überweisung §8» §7";
    private static final String UI = "ethicbuilds:ui";
    private static final String BOLD = "ethicbuilds:ui_bold";
    private static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    private static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    private static final TextColor GOLD = TextColor.fromHexString("#FFD24A");
    private static final TextColor RED = TextColor.fromHexString("#E05252");
    private static final TextColor DARK = TextColor.fromHexString("#142536");
    private static final TextColor LOCKED = TextColor.fromHexString("#6B7C90");
    private static final String COIN = "icon_muenze_20_255-210-74";
    private static final long MAX_AMOUNT = 1_000_000_000_000L;

    private final Plugin plugin;
    private final Prompts prompts;

    public TransferPage(Plugin plugin, Prompts prompts) {
        this.plugin = plugin;
        this.prompts = prompts;
    }

    @Override
    public Set<String> pages() {
        return Set.of("ueberweisen");
    }

    /** Beim Betreten der Seite beginnt das Formular leer. */
    @Override
    public void load(Player player, Map<String, Object> data, String page) {
        data.remove("uTo");
        data.remove("uToName");
        data.remove("uAmount");
        data.remove("uBusy");
    }

    // ------------------------------------------------------------ Zeichnen

    @Override
    public void draw(Board board, Session s, String page) {
        Element form = element(board, page, "u_form");
        Element sum = element(board, page, "u_summe");
        if (form != null) {
            form(board, s, form);
        }
        if (sum != null) {
            summary(board, s, sum);
        }
    }

    private void form(Board board, Session s, Element card) {
        double left = card.x() - card.width() / 2 + 0.16;
        double right = card.x() + card.width() / 2 - 0.16;
        double top = card.y() + card.height() / 2;
        long amount = amount(s);
        String name = string(s, "uToName");

        text(board, s, left, top - 0.2, "Coins senden", BOLD, 24, TEXT, Align.LEFT);

        // Empfaenger: Pille, Klick fragt den Namen im Chat ab
        text(board, s, left, top - 0.54, "Empfänger", UI, 15, HINT, Align.LEFT);
        double pillX = left + 0.75;
        double pillY = top - 0.78;
        board.glyph(s, pillX, pillY, "pille_240x52_22-42-64", null);
        if (name.isEmpty()) {
            text(board, s, pillX, pillY, "Spieler wählen", UI, 17, LOCKED, Align.CENTER);
        } else {
            text(board, s, pillX, pillY, name, BOLD, 19, TEXT, Align.CENTER);
        }
        board.region(s, "u_empfaenger", pillX, pillY, 1.5, 0.325, "u_empfaenger");

        // Betrag: Anzeige (orange umrandet) und Reihe -10 -1 +1 +10
        text(board, s, left, top - 1.14, "Betrag", UI, 15, HINT, Align.LEFT);
        double showX = left + 0.8315;
        double showY = top - 1.38;
        board.glyph(s, showX, showY, "pille_266x48_52-48-39_249-161-30_2", null);
        coinValue(board, s, showX, showY, amount, 24, GOLD, Align.CENTER);
        board.region(s, "u_betrag", showX, showY, 1.663, 0.3, "u_betrag");
        String[] labels = {"-10", "-1", "+1", "+10"};
        String[] ids = {"u_minus10", "u_minus1", "u_plus1", "u_plus10"};
        double rowY = top - 1.78;
        for (int i = 0; i < 4; i++) {
            double x = left + 0.2815 + i * 0.593;
            board.button(s, ids[i], x, rowY, 0.563, 0.25, ids[i], "pille_90x40_31-58-89", "pille_90x40_31-58-89_hover");
            text(board, s, x, rowY, labels[i], BOLD, 17, TEXT, Align.CENTER);
        }

        // Guthaben und Hinweise
        long coins = number(s, "coins");
        text(board, s, left, top - 2.2, "Guthaben", UI, 17, HINT, Align.LEFT);
        coinValue(board, s, right, top - 2.2, coins, 17, GOLD, Align.RIGHT);
        if (amount > coins) {
            text(board, s, left, top - 2.52, "Dafür reicht dein Guthaben nicht.", UI, 15, RED, Align.LEFT);
        }
        List<String> hint = board.wrap("Klicke auf Empfänger oder Betrag und tippe den Wert in den Chat. Auf Überweisungen fällt Steuer an.",
                UI, 14, right - left);
        for (int i = 0; i < hint.size() && i < 3; i++) {
            text(board, s, left, top - 2.88 - i * 0.17, hint.get(i), UI, 14, HINT, Align.LEFT);
        }
    }

    private void summary(Board board, Session s, Element card) {
        double left = card.x() - card.width() / 2 + 0.16;
        double right = card.x() + card.width() / 2 - 0.16;
        double top = card.y() + card.height() / 2;
        long amount = amount(s);
        long tax = tax(amount);
        long received = Math.max(0, amount - tax);
        String name = string(s, "uToName");
        double rate = s.data().get("tax") instanceof Number n ? n.doubleValue() : 0;

        text(board, s, left, top - 0.2, "Zusammenfassung", BOLD, 24, TEXT, Align.LEFT);
        row(board, s, left, right, top - 0.62, "Empfänger", name.isEmpty() ? "-" : name, TEXT);
        text(board, s, left, top - 1.0, "Betrag", UI, 17, HINT, Align.LEFT);
        coinValue(board, s, right, top - 1.0, amount, 17, GOLD, Align.RIGHT);
        text(board, s, left, top - 1.36, "Steuer " + String.format(Locale.GERMANY, "%.1f", rate) + " %", UI, 17, HINT, Align.LEFT);
        coinValue(board, s, right, top - 1.36, tax, 17, GOLD, Align.RIGHT);
        board.glyph(s, card.x(), top - 1.62, "trenner_286x2_41-59-79", null);
        text(board, s, left, top - 1.88, "Empfänger erhält", UI, 17, TEXT, Align.LEFT);
        coinValue(board, s, right, top - 1.88, received, 19, GOLD, Align.RIGHT);

        // Hauptknopf: nur bedienbar mit Empfaenger, Betrag und genug Guthaben
        double sendY = top - 2.7;
        if (canSend(s)) {
            board.button(s, "u_senden", card.x(), sendY, 1.438, 0.3, "u_senden", "knopf_230x48_haupt_t", "knopf_230x48_haupt_hover_t");
            text(board, s, card.x(), sendY, "Überweisen", BOLD, 19, DARK, Align.CENTER);
        } else {
            board.glyph(s, card.x(), sendY, "pille_230x52_22-42-64", null);
            text(board, s, card.x(), sendY, "Überweisen", UI, 19, LOCKED, Align.CENTER);
        }
        double cancelY = top - 3.25;
        board.button(s, "u_abbrechen", card.x(), cancelY, 0.938, 0.275, "u_abbrechen", "knopf_150x44_normal_k", "knopf_150x44_hover_k");
        text(board, s, card.x(), cancelY, "Abbrechen", UI, 17, TEXT, Align.CENTER);
    }

    // ------------------------------------------------------------ Klicks

    @Override
    public boolean click(Board board, Session s, Element e) {
        switch (e.id()) {
            case "u_empfaenger" -> askRecipient(board, s);
            case "u_betrag" -> askAmount(board, s);
            case "u_minus10" -> change(board, s, -10);
            case "u_minus1" -> change(board, s, -1);
            case "u_plus1" -> change(board, s, 1);
            case "u_plus10" -> change(board, s, 10);
            case "u_senden" -> send(board, s);
            case "u_abbrechen" -> {
                reset(s);
                board.refresh(s);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void change(Board board, Session s, long delta) {
        s.data().put("uAmount", Math.max(0, Math.min(MAX_AMOUNT, amount(s) + delta)));
        board.refresh(s);
    }

    private void askRecipient(Board board, Session s) {
        Player player = s.player();
        prompts.ask(player, "Wie heißt der Empfänger?", text -> {
            Player online = Bukkit.getPlayerExact(text);
            OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayerIfCached(text);
            if (target == null) {
                player.sendMessage(PREFIX + "§cSpieler " + text + " ist unbekannt.");
                return;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(PREFIX + "§cDu kannst dir nicht selbst Coins überweisen.");
                return;
            }
            s.data().put("uTo", target.getUniqueId());
            s.data().put("uToName", target.getName() != null ? target.getName() : text);
            refreshIfCurrent(board, s);
        });
    }

    private void askAmount(Board board, Session s) {
        prompts.ask(s.player(), "Wie viele Coins möchtest du senden?", text -> {
            long value = parse(text);
            if (value < 1) {
                s.player().sendMessage(PREFIX + "§cDer Betrag muss eine ganze Zahl ab 1 sein.");
                return;
            }
            s.data().put("uAmount", Math.min(MAX_AMOUNT, value));
            refreshIfCurrent(board, s);
        });
    }

    private void send(Board board, Session s) {
        if (!canSend(s) || s.data().containsKey("uBusy")) {
            return;
        }
        Player player = s.player();
        UUID to = (UUID) s.data().get("uTo");
        String toName = string(s, "uToName");
        long amount = amount(s);
        TransferApi api = plugin.getServer().getServicesManager().load(TransferApi.class);
        if (api == null) {
            player.sendMessage(PREFIX + "§cÜberweisungen sind gerade nicht verfügbar.");
            return;
        }
        s.data().put("uBusy", Boolean.TRUE);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TransferApi.Result result = api.transfer(player.getUniqueId(), to, amount);
            // Nach Erfolg alle Werte (Guthaben) frisch lesen
            Map<String, Object> fresh = result.outcome() == TransferApi.Outcome.OK ? new CharacterData(plugin).load(player) : null;
            Bukkit.getScheduler().runTask(plugin, () -> {
                s.data().remove("uBusy");
                report(player, toName, to, amount, result);
                if (result.outcome() == TransferApi.Outcome.OK) {
                    if (fresh != null) {
                        s.data().putAll(fresh);
                    }
                    reset(s);
                }
                refreshIfCurrent(board, s);
            });
        });
    }

    private void report(Player player, String toName, UUID to, long amount, TransferApi.Result result) {
        switch (result.outcome()) {
            case OK -> {
                player.sendMessage(PREFIX + "§a" + CoinFormat.number(amount) + " Coins an §f" + toName + " §aüberwiesen. §7Steuer: §6"
                        + CoinFormat.number(result.tax()) + "§7, " + toName + " erhält §6" + CoinFormat.number(result.received()) + "§7.");
                Player receiver = Bukkit.getPlayer(to);
                if (receiver != null) {
                    receiver.sendMessage(PREFIX + "§f" + player.getName() + " §7hat dir §6" + CoinFormat.number(result.received())
                            + " Coins §7überwiesen.");
                }
            }
            case NO_FUNDS -> player.sendMessage(PREFIX + "§cDafür fehlen dir Coins.");
            case SELF -> player.sendMessage(PREFIX + "§cDu kannst dir nicht selbst Coins überweisen.");
            case BAD_AMOUNT -> player.sendMessage(PREFIX + "§cDer Betrag muss mindestens 1 sein.");
            default -> player.sendMessage(PREFIX + "§cDie Überweisung ist fehlgeschlagen, deine Coins sind zurück.");
        }
    }

    // ------------------------------------------------------------ Hilfen

    private static void reset(Session s) {
        s.data().remove("uTo");
        s.data().remove("uToName");
        s.data().remove("uAmount");
    }

    // Nur neu zeichnen, wenn die Tafel noch offen und diese Seite zu sehen ist
    private static void refreshIfCurrent(Board board, Session s) {
        if (board.session(s.player()) == s && "ueberweisen".equals(s.page())) {
            board.refresh(s);
        }
    }

    private boolean canSend(Session s) {
        long amount = amount(s);
        return s.data().get("uTo") instanceof UUID && amount >= 1 && amount <= number(s, "coins");
    }

    private long tax(long amount) {
        AuctionApi auction = plugin.getServer().getServicesManager().load(AuctionApi.class);
        return auction != null && amount > 0 ? Math.min(amount, auction.taxOn(amount)) : 0;
    }

    private static long parse(String text) {
        try {
            return Long.parseLong(text.replace(".", "").replace(",", "").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // Zeile mit Beschriftung links und Text rechts
    private void row(Board board, Session s, double left, double right, double y, String label, String value, TextColor color) {
        text(board, s, left, y, label, UI, 17, HINT, Align.LEFT);
        text(board, s, right, y, value, BOLD, 17, color, Align.RIGHT);
    }

    private void text(Board board, Session s, double x, double y, String text, String font, int px, TextColor color, Align align) {
        board.line(s, x, y, Component.text(text).color(color), text, font, px, align);
    }

    // Muenzsymbol vor der Zahl, an x links, rechts oder mittig
    private void coinValue(Board board, Session s, double x, double y, long value, int px, TextColor color, Align align) {
        String number = CoinFormat.number(value);
        double total = 0.125 + 0.05 + board.measure(number, BOLD, px);
        double start = switch (align) {
            case RIGHT -> x - total;
            case CENTER -> x - total / 2;
            default -> x;
        };
        board.glyph(s, start + 0.0625, y, COIN, null);
        board.line(s, start + 0.175, y, Component.text(number).color(color), number, BOLD, px, Align.LEFT);
    }

    private static Element element(Board board, String page, String id) {
        for (Element e : board.layout().page(page)) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    private static long amount(Session s) {
        return s.data().get("uAmount") instanceof Number n ? n.longValue() : 0;
    }

    private static long number(Session s, String key) {
        return s.data().get(key) instanceof Number n ? n.longValue() : 0;
    }

    private static String string(Session s, String key) {
        Object value = s.data().get(key);
        return value != null ? value.toString() : "";
    }
}
