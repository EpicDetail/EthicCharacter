package de.ethicbuilds.character.page.trader;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.page.PageHandler;
import de.ethicbuilds.economy.api.EconomyApi;
import de.ethicbuilds.economy.format.CoinFormat;
import de.ethicbuilds.survival.api.TraderApi;
import de.ethicbuilds.survival.api.TraderApi.Item;
import de.ethicbuilds.survival.api.TraderApi.Period;
import de.ethicbuilds.survival.api.TraderApi.TaskView;
import de.ethicbuilds.survival.auction.ItemNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Seite "haendler": drei Reihen (stuendlich, taeglich, woechentlich) mit je drei Aufgabenkarten. Die Karten sind fertige Bilder
 * aus dem Generator (haendler/karte/zustand_n), diese Klasse setzt Items, Texte und Klicks darauf. Ohne Haendler-Dienst
 * zeigt sie die Seite "haendler_leer".
 */
public final class HaendlerPage implements PageHandler {

    private static final String PAGE = "haendler";
    private static final String EMPTY = "haendler_leer";
    private static final String UI = "ethicbuilds:ui";
    private static final String BOLD = "ethicbuilds:ui_bold";
    private static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    private static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    private static final TextColor GOLD = TextColor.fromHexString("#FFD24A");
    private static final TextColor ORANGE = TextColor.fromHexString("#F9A11E");
    private static final TextColor GREEN = TextColor.fromHexString("#2DBE6C");
    private static final TextColor RED = TextColor.fromHexString("#E05252");
    private static final TextColor DARK = TextColor.fromHexString("#142536");
    private static final TextColor LOCKED = TextColor.fromHexString("#6B7C90");
    private static final TextColor DIM_BASE = TextColor.fromHexString("#162A40");
    private static final Period[] PERIODS = Period.values();
    private static final String[] IDS = {"staendlich", "taeglich", "woechentlich"};
    private static final String[] NAMES = {"Stündlich", "Täglich", "Wöchentlich"};

    // Schluessel in session.data()
    private static final String AVAILABLE = "t_da";
    private static final String TASKS = "t_aufgaben";
    private static final String ENDS = "t_ende";
    private static final String BUSY = "t_beschaeftigt";
    private static final String SEQ = "t_folge";

    private final Plugin plugin;

    public HaendlerPage(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Set<String> pages() {
        return Set.of(PAGE, EMPTY);
    }

    // ------------------------------------------------------------ Laden

    /** Aufgaben je Zeitraum, Restzeiten und Kontostand holen (Datenbank, nicht im Hauptthread). */
    @Override
    public void load(Player player, Map<String, Object> data, String page) {
        data.putAll(fetch(player.getUniqueId()));
        data.put(BUSY, false);
        data.putIfAbsent(SEQ, 0);
    }

    private Map<String, Object> fetch(UUID id) {
        Map<String, Object> fresh = new HashMap<>();
        TraderApi api = api();
        EconomyApi economy = Bukkit.getServicesManager().load(EconomyApi.class);
        if (economy != null) {
            fresh.put("coins", economy.balance(id));
        }
        if (api == null) {
            fresh.put(AVAILABLE, false);
            return fresh;
        }
        Map<Period, List<TaskView>> tasks = new HashMap<>();
        long[] ends = new long[PERIODS.length];
        long now = System.currentTimeMillis();
        for (Period period : PERIODS) {
            tasks.put(period, api.tasks(id, period));
            ends[period.ordinal()] = now + api.secondsLeft(period) * 1000L;
        }
        fresh.put(AVAILABLE, true);
        fresh.put(TASKS, tasks);
        fresh.put(ENDS, ends);
        return fresh;
    }

    // Neu laden (Hintergrund) und danach zeichnen; die neueste Anfrage gewinnt
    private void reload(Board board, Session s) {
        Player player = s.player();
        UUID id = player.getUniqueId();
        int seq = number(s, SEQ) + 1;
        s.data().put(SEQ, seq);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Object> fresh = fetch(id);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (board.session(player) == s && pages().contains(s.page()) && number(s, SEQ) == seq) {
                    s.data().putAll(fresh);
                    s.data().put(BUSY, false);
                    board.show(s, available(s) ? PAGE : EMPTY);
                }
            });
        });
    }

    // ------------------------------------------------------------ Texte und Sichtbarkeit

    @Override
    public Component content(Session s, Element e) {
        // Restzeit-Pillen beschriftet draw() selbst (Text schrumpft, bis er passt)
        return e.id().endsWith("_rest") ? Component.empty() : null;
    }

    @Override
    public boolean visible(Session s, Element e) {
        String id = e.id();
        // Karten (mit Kacheln, Pillen, Knopf, Texten) und die linksbuendigen Texte zeichnet draw()
        return !(id.startsWith("trader_karte_") || id.equals("h_titel") || id.equals("h_untertitel")
                || (id.startsWith("h_") && (id.endsWith("_name") || id.endsWith("_sub"))));
    }

    // ------------------------------------------------------------ Zeichnen

    @Override
    public void draw(Board board, Session s, String page) {
        if (!available(s)) {
            if (!page.equals(EMPTY)) {
                board.show(s, EMPTY);
                return;
            }
        } else if (page.equals(EMPTY)) {
            board.show(s, PAGE);
            return;
        }
        Map<String, Element> el = new HashMap<>();
        for (Element e : board.layout().page(page)) {
            el.put(e.id(), e);
        }
        header(board, s, el);
        if (!page.equals(PAGE)) {
            return;
        }
        Map<Period, List<TaskView>> tasks = tasks(s);
        TraderApi api = api();
        for (int p = 0; p < PERIODS.length; p++) {
            rowHead(board, s, el, p);
            List<TaskView> list = tasks.getOrDefault(PERIODS[p], List.of());
            for (int i = 0; i < list.size() && i < 3; i++) {
                card(board, s, el, p, i, list.get(i), api);
            }
        }
    }

    // Titel und Untertitel, linksbuendig an der Kante des Layout-Kastens
    private void header(Board board, Session s, Map<String, Element> el) {
        Element title = el.get("h_titel");
        if (title != null) {
            board.line(s, title.x() - title.width() / 2, title.y(), Component.text(title.text()).color(TEXT), title.text(), BOLD, title.px(), Align.LEFT);
        }
        Element sub = el.get("h_untertitel");
        if (sub != null) {
            board.line(s, sub.x() - sub.width() / 2, sub.y(), Component.text(sub.text()).color(HINT), sub.text(), UI, sub.px(), Align.LEFT);
        }
    }

    // Name des Zeitraums, Restzeit in der Pille und Erklaerung (umgebrochen) links neben den Karten
    private void rowHead(Board board, Session s, Map<String, Element> el, int p) {
        String id = IDS[p];
        Element name = el.get("h_" + id + "_name");
        if (name != null) {
            board.line(s, name.x() - name.width() / 2, name.y(), Component.text(NAMES[p]).color(TEXT), NAMES[p], BOLD, name.px(), Align.LEFT);
        }
        Element rest = el.get("h_" + id + "_rest");
        if (rest != null) {
            restTime(board, s, rest, p);
        }
        Element sub = el.get("h_" + id + "_sub");
        if (sub != null) {
            List<String> lines = board.wrap(sub.text(), UI, sub.px(), sub.width());
            double step = sub.px() * 1.45 / 160.0;
            double y = sub.y() + (lines.size() - 1) * step / 2;
            for (String line : lines) {
                board.line(s, sub.x() - sub.width() / 2, y, Component.text(line).color(HINT), line, UI, sub.px(), Align.LEFT);
                y -= step;
            }
        }
    }

    // "noch 23 Min." in der Pille, rechts vom Uhr-Symbol; zu lang: ohne "noch", dann kleinere Schrift
    private void restTime(Board board, Session s, Element pill, int p) {
        long[] ends = s.data().get(ENDS) instanceof long[] values ? values : new long[PERIODS.length];
        String time = duration(Math.max(0, ends[p] - System.currentTimeMillis()) / 1000);
        double icon = Double.parseDouble(pill.extra("iconPx", "15")) / 160.0;
        double left = pill.x() - pill.width() / 2 + 0.06 + icon + 0.035;
        double max = pill.width() - (left - (pill.x() - pill.width() / 2)) - 0.05;
        String text = "noch " + time;
        if (board.measure(text, UI, 11) > max) {
            text = time;
        }
        int px = fit(board, text, UI, pill.px(), max, 9);
        board.line(s, left, pill.y(), Component.text(text).color(TEXT), text, UI, px, Align.LEFT);
    }

    // Eine Aufgabenkarte: Bild nach Zustand, Posten mit Bestand, Belohnung, Knopf-Beschriftung
    private void card(Board board, Session s, Map<String, Element> el, int p, int i, TaskView task, TraderApi api) {
        String base = "trader_karte_" + IDS[p] + "_" + i;
        Element card = el.get(base);
        if (card == null) {
            return;
        }
        List<Item> items = task.items();
        int count = Math.max(1, Math.min(3, items.size()));
        boolean ready = !task.done();
        int[] have = new int[items.size()];
        for (int k = 0; k < items.size(); k++) {
            have[k] = api != null ? api.owned(s.player(), items.get(k).material()) : 0;
            ready &= have[k] >= items.get(k).amount();
        }
        String state = task.done() ? "erledigt" : ready ? "bereit" : "fehlt";
        String normal = "haendler/karte/" + state + "_" + count;
        if (ready) {
            board.button(s, base, card.x(), card.y(), card.width(), card.height(), "abgeben", normal, "haendler/karte/hover_" + count);
        } else {
            board.glyph(s, card.x(), card.y(), normal, null);
        }
        for (int k = 0; k < items.size() && k < 3; k++) {
            row(board, s, el, base, k, items.get(k), have[k], task.done());
        }
        rewards(board, s, el, base, task);
        Element button = el.get(base + "_knopf");
        if (button != null) {
            String label = task.done() ? "Erledigt" : ready ? "Abgeben" : "Fehlt noch";
            TextColor color = task.done() ? GREEN : ready ? DARK : LOCKED;
            double shift = task.done() ? 0.045 : 0;
            board.line(s, button.x() + shift, button.y(), Component.text(label).color(color), label, UI, fit(board, label, UI, button.px(), button.width() - 0.2, 11), Align.CENTER);
        }
    }

    // Posten: Item in der Kachel, "<menge>x <Name>" und "hast X / Y" (gruen bei genug, sonst rot)
    private void row(Board board, Session s, Map<String, Element> el, String base, int k, Item item, int have, boolean done) {
        Element tile = el.get(base + "_p" + k + "_kachel");
        Element name = el.get(base + "_p" + k + "_name");
        Element count = el.get(base + "_p" + k + "_hast");
        if (tile != null) {
            board.item(s, tile.x(), tile.y(), tile.width(), new ItemStack(item.material()));
        }
        if (name != null) {
            String text = item.amount() + "x " + ItemNames.german(item.material());
            int px = fit(board, text, UI, name.px(), name.width(), 11);
            String shown = ellipsize(board, text, UI, px, name.width());
            board.line(s, name.x() - name.width() / 2, name.y(), Component.text(shown).color(done ? dim(TEXT) : TEXT), shown, UI, px, Align.LEFT);
        }
        if (count != null && !done) {
            String text = "hast " + CoinFormat.number(have) + " / " + CoinFormat.number(item.amount());
            int px = fit(board, text, UI, count.px(), count.width(), 10);
            board.line(s, count.x() - count.width() / 2, count.y(), Component.text(text).color(have >= item.amount() ? GREEN : RED), text, UI, px, Align.LEFT);
        }
    }

    // Belohnung: "+<Coins>" rechts neben dem Muenzsymbol und "+<XP> XP" mittig in der Pille
    private void rewards(Board board, Session s, Map<String, Element> el, String base, TaskView task) {
        Element coins = el.get(base + "_coins");
        if (coins != null) {
            String text = "+" + CoinFormat.number(task.coins());
            double left = coins.x() - coins.width() / 2 + 0.06 + Math.round(Math.min(coins.width(), coins.height()) * 160 * 0.55) / 160.0 + 0.03;
            double max = coins.x() + coins.width() / 2 - 0.04 - left;
            int px = fit(board, text, UI, coins.px(), max, 9);
            board.line(s, left + max / 2, coins.y(), Component.text(text).color(task.done() ? dim(GOLD) : GOLD), text, UI, px, Align.CENTER);
        }
        Element xp = el.get(base + "_xp");
        if (xp != null) {
            String text = "+" + CoinFormat.number(task.xp()) + " XP";
            int px = fit(board, text, UI, xp.px(), xp.width() - 0.08, 9);
            board.line(s, xp.x(), xp.y(), Component.text(text).color(task.done() ? dim(ORANGE) : ORANGE), text, UI, px, Align.CENTER);
        }
    }

    // ------------------------------------------------------------ Klicks

    @Override
    public boolean click(Board board, Session s, Element e) {
        String id = e.id();
        if (!id.startsWith("trader_karte_")) {
            return false;
        }
        // trader_karte_<zeitraum>_<nummer>
        String[] parts = id.split("_");
        int p = parts.length == 4 ? indexOf(parts[2]) : -1;
        int idx = parts.length == 4 ? parseInt(parts[3]) : -1;
        TraderApi api = api();
        Player player = s.player();
        if (p < 0 || idx < 0 || api == null || Boolean.TRUE.equals(s.data().get(BUSY))) {
            return true;
        }
        s.data().put(BUSY, true);
        long[] ends = s.data().get(ENDS) instanceof long[] values ? values : new long[PERIODS.length];
        if (System.currentTimeMillis() >= ends[p]) {
            // Zeitraum ist inzwischen gewechselt: neue Aufgaben laden statt eine falsche Karte abzugeben
            player.sendMessage("§6Händler §8» §7Die Aufgaben haben gewechselt.");
            reload(board, s);
            return true;
        }
        api.deliver(player, PERIODS[p], idx, result -> {
            if (!result.message().isEmpty()) {
                player.sendMessage(result.message());
            }
            reload(board, s);
        });
        return true;
    }

    // ------------------------------------------------------------ Hilfen

    private TraderApi api() {
        return Bukkit.getServicesManager().load(TraderApi.class);
    }

    private static boolean available(Session s) {
        return Boolean.TRUE.equals(s.data().get(AVAILABLE));
    }

    @SuppressWarnings("unchecked")
    private static Map<Period, List<TaskView>> tasks(Session s) {
        return s.data().get(TASKS) instanceof Map<?, ?> map ? (Map<Period, List<TaskView>>) map : Map.of();
    }

    private static int indexOf(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int number(Session s, String key) {
        return s.data().get(key) instanceof Number n ? n.intValue() : 0;
    }

    // Farbe zur Karte hin abgedunkelt (erledigte Karten sind auf 55 % gedimmt)
    private static TextColor dim(TextColor color) {
        TextColor base = TextColor.fromHexString("#122336");
        return TextColor.color((int) Math.round(color.red() * 0.55 + base.red() * 0.45), (int) Math.round(color.green() * 0.55 + base.green() * 0.45),
                (int) Math.round(color.blue() * 0.55 + base.blue() * 0.45));
    }

    // "23 Min." | "4 Std. 12 Min." | "3 Tg. 5 Std."
    static String duration(long seconds) {
        long minutes = Math.max(1, (Math.max(0, seconds) + 59) / 60);
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        if (days > 0) {
            return days + " Tg. " + hours + " Std.";
        }
        return hours > 0 ? hours + " Std. " + (minutes % 60) + " Min." : minutes + " Min.";
    }

    private static int fit(Board board, String text, String font, int px, double max, int min) {
        int result = px;
        while (result > min && board.measure(text, font, result) > max) {
            result--;
        }
        return result;
    }

    private static String ellipsize(Board board, String text, String font, int px, double max) {
        String shown = text;
        while (shown.length() > 3 && board.measure(shown + "..", font, px) > max) {
            shown = shown.substring(0, shown.length() - 1);
        }
        return shown.equals(text) ? text : shown + "..";
    }
}
