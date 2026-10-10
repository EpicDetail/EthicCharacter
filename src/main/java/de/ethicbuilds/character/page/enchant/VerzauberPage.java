package de.ethicbuilds.character.page.enchant;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.page.CharacterData;
import de.ethicbuilds.character.page.PageHandler;
import de.ethicbuilds.economy.format.CoinFormat;
import de.ethicbuilds.survival.api.EnchantApi;
import de.ethicbuilds.survival.api.EnchantApi.EnchantView;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seite "verzauberer": Gegenstand in der Hand links, rechts die moeglichen Verzauberungen (bis zu sechs) mit Kosten.
 * Die Zeilen werden hier selbst gezeichnet (Zustand normal oder gesperrt), die Logik liegt im EthicSurvival (EnchantApi).
 */
public final class VerzauberPage implements PageHandler {

    private static final String PAGE = "verzauberer";
    private static final int ROWS = 6;
    private static final Pattern ROW = Pattern.compile("v_(ench|zeile)_(\\d)(?:_(.+))?");
    private static final String FONT = "ethicbuilds:ui";
    private static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    private static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    private static final TextColor ORANGE = TextColor.fromHexString("#F9A11E");
    private static final TextColor GOLD = TextColor.fromHexString("#FFD24A");
    private static final TextColor GREEN = TextColor.fromHexString("#2DBE6C");
    private static final TextColor RED = TextColor.fromHexString("#E05252");
    private static final TextColor LOCKED = TextColor.fromHexString("#6B7C90");
    private static final TextColor BUTTON = TextColor.fromHexString("#142536");

    private final Plugin plugin;
    private final CharacterData characterData;

    public VerzauberPage(Plugin plugin, CharacterData characterData) {
        this.plugin = plugin;
        this.characterData = characterData;
    }

    @Override
    public Set<String> pages() {
        return Set.of(PAGE);
    }

    // ------------------------------------------------------------ Laden

    /** Liest die Hand (im Server-Thread) und die moeglichen Schritte (Datenbank), laeuft im Hintergrund. */
    @Override
    public void load(Player player, Map<String, Object> data, String page) {
        ItemStack hand = hand(player);
        if (hand == null || hand.getType().isAir()) {
            data.remove("enchItem");
            data.put("enchViews", new ArrayList<EnchantView>());
            return;
        }
        EnchantApi api = plugin.getServer().getServicesManager().load(EnchantApi.class);
        List<EnchantView> views = api != null ? api.options(player, hand) : List.of();
        data.put("enchItem", hand);
        data.put("enchViews", new ArrayList<>(views));
    }

    private ItemStack hand(Player player) {
        if (Bukkit.isPrimaryThread()) {
            return player.getInventory().getItemInMainHand().clone();
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, () -> player.getInventory().getItemInMainHand().clone())
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // Neue Werte holen (Kontostand, Level, Hand, Optionen) und die Seite neu zeichnen
    private void reload(Board board, Session s) {
        Player player = s.player();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Object> values = characterData.load(player);
            load(player, values, PAGE);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (board.session(player) == s && PAGE.equals(s.page())) {
                    if (!values.containsKey("enchItem")) {
                        s.data().remove("enchItem");
                    }
                    s.data().putAll(values);
                    board.refresh(s);
                }
            });
        });
    }

    // ------------------------------------------------------------ Sichtbarkeit

    @Override
    public boolean visible(Session s, Element e) {
        // Alle Zeilen (Name, Pips, Kosten, Knopf) zeichnet draw() selbst
        if (ROW.matcher(e.id()).matches()) {
            return false;
        }
        return switch (e.id()) {
            case "v_gs_name", "v_hinweis" -> false;
            case "v_gs_hand", "v_gs_trenner", "v_gs_kachel" -> item(s) != null;
            default -> true;
        };
    }

    // ------------------------------------------------------------ Zeichnen

    @Override
    public void draw(Board board, Session s, String page) {
        Map<String, Element> el = new HashMap<>();
        for (Element e : board.layout().page(page)) {
            el.put(e.id(), e);
        }
        ItemStack item = item(s);
        List<EnchantView> views = views(s);
        Element left = el.get("v_gegenstand");
        Element right = el.get("v_auf_karte");
        if (item == null) {
            board.line(s, left.x(), left.y() + 0.1, Component.text("Kein Gegenstand in der Hand").color(HINT), "Kein Gegenstand in der Hand",
                    FONT, 20, Align.CENTER);
            board.line(s, left.x(), left.y() - 0.15, Component.text("Halte ihn in der Haupthand.").color(LOCKED), "Halte ihn in der Haupthand.",
                    FONT, 15, Align.CENTER);
            board.line(s, right.x(), right.y() + 0.1, Component.text("Halte einen Gegenstand in der Hand,").color(LOCKED),
                    "Halte einen Gegenstand in der Hand,", FONT, 18, Align.CENTER);
            board.line(s, right.x(), right.y() - 0.15, Component.text("dann siehst du hier die Aufwertungen.").color(LOCKED),
                    "dann siehst du hier die Aufwertungen.", FONT, 18, Align.CENTER);
            return;
        }
        drawItem(board, s, el, item);
        if (views.isEmpty()) {
            board.line(s, right.x(), right.y() + 0.12, Component.text("Keine passenden Bücher").color(HINT), "Keine passenden Bücher",
                    FONT, 24, Align.CENTER);
            String hint = "Zauberbücher landen auf deinem Konto, /buecher zeigt sie dir.";
            double y = right.y() - 0.12;
            for (String line : board.wrap(hint, FONT, 16, right.width() - 0.8)) {
                board.line(s, right.x(), y, Component.text(line).color(LOCKED), line, FONT, 16, Align.CENTER);
                y -= 0.18;
            }
            return;
        }
        long xpInto = number(s, "xpInto");
        long coins = number(s, "coins");
        int shown = Math.min(ROWS, views.size());
        for (int i = 0; i < shown; i++) {
            drawPreview(board, s, el, i, views.get(i));
            drawRow(board, s, el, i, views.get(i), xpInto, coins);
        }
        if (views.size() > ROWS) {
            String more = "+ " + (views.size() - ROWS) + " weitere";
            Element sub = el.get("v_auf_sub");
            board.line(s, right.x() + right.width() / 2 - 0.2, sub.y(), Component.text(more).color(LOCKED), more, FONT, 16, Align.RIGHT);
        }
        Element hint = el.get("v_hinweis");
        String warning = hint.text();
        double rightEdge = hint.x() + hint.width() / 2;
        board.line(s, rightEdge, hint.y(), Component.text(warning).color(HINT), warning, FONT, hint.px(), Align.RIGHT);
        String icon = hint.extra("iconBild", "");
        double size = Double.parseDouble(hint.extra("iconPx", "18")) / 160.0;
        board.glyph(s, rightEdge - board.measure(warning, FONT, hint.px()) - 0.05 - size / 2, hint.y(), icon, null);
    }

    // Item-Kachel mit dem Gegenstand und seinem Namen
    private void drawItem(Board board, Session s, Map<String, Element> el, ItemStack item) {
        Element tile = el.get("v_gs_kachel");
        board.item(s, tile.x(), tile.y(), 0.42, item);
        Element name = el.get("v_gs_name");
        ItemMeta meta = item.getItemMeta();
        Component title = meta != null && meta.hasDisplayName() ? meta.displayName() : Component.translatable(item.translationKey());
        board.text(s, name.x(), name.y(), title.colorIfAbsent(TEXT).font(Key.key(FONT)), 20);
    }

    // Linke Karte: Name, Pips der Zielstufe und Stufenzahl der Verzauberung
    private void drawPreview(Board board, Session s, Map<String, Element> el, int i, EnchantView view) {
        Element name = el.get("v_ench_" + i + "_name");
        Element pips = el.get("v_ench_" + i + "_pips");
        Element level = el.get("v_ench_" + i + "_stufe");
        TextColor color = TextColor.color(view.colorRgb());
        String plain = view.name();
        int px = fit(board, plain, name.px(), name.width());
        board.line(s, name.x() - name.width() / 2, name.y(), Component.text(plain).color(color), plain, FONT, px, Align.LEFT);
        if (view.single()) {
            // Ohne Stufen: die Seltenheit steht an der Stelle der Pips
            board.line(s, pips.x() - pips.width() / 2, pips.y(), Component.text(view.rarityLabel()).color(color), view.rarityLabel(), FONT, 15,
                    Align.LEFT);
            return;
        }
        String glyph = pips.images().get(String.valueOf(Math.max(0, Math.min(9, view.target()))));
        if (glyph != null) {
            board.glyph(s, pips.x(), pips.y(), glyph, null);
        }
        String number = String.valueOf(view.target());
        board.line(s, level.x(), level.y(), Component.text(number).color(ORANGE), number, FONT, level.px(), Align.CENTER);
    }

    // Rechte Karte: eine Zeile mit Namen, Kosten, Fehlschlag und Knopf
    private void drawRow(Board board, Session s, Map<String, Element> el, int i, EnchantView view, long xpInto, long coins) {
        String p = "v_zeile_" + i;
        Element row = el.get(p);
        Element name = el.get(p + "_name");
        Element sub = el.get(p + "_sub");
        Element xp = el.get(p + "_xp");
        Element coin = el.get(p + "_coins");
        Element chance = el.get(p + "_chance");
        Element button = el.get(p + "_knopf");
        boolean noXp = view.xp() > xpInto;
        boolean noCoins = view.coins() > coins;
        boolean locked = noXp || noCoins;
        String glyph = row.images().get(locked ? "gesperrt" : "normal");
        if (glyph != null) {
            board.glyph(s, row.x(), row.y(), glyph, null);
        }
        double nameLeft = name.x() - name.width() / 2;
        TextColor color = TextColor.color(view.colorRgb());
        int namePx = fit(board, view.name(), name.px(), name.width());
        board.line(s, nameLeft, name.y(), Component.text(view.name()).color(color), view.name(), FONT, namePx, Align.LEFT);
        String detail = view.single() ? view.rarityLabel() : "von Stufe " + (view.target() - 1) + " auf " + view.target();
        if (!view.effectText().isEmpty()) {
            detail += " · " + view.effectText();
        }
        int subPx = Math.max(11, fit(board, detail, sub.px(), sub.width()));
        detail = clip(board, detail, subPx, sub.width());
        board.line(s, nameLeft, sub.y(), Component.text(detail).color(HINT), detail, FONT, subPx, Align.LEFT);
        // Kosten
        String xpText = CoinFormat.number(view.xp()) + " XP";
        board.line(s, xp.x(), xp.y(), Component.text(xpText).color(noXp ? RED : TEXT), xpText, FONT, fit(board, xpText, xp.px(), xp.width()),
                Align.CENTER);
        String coinText = CoinFormat.number(view.coins());
        double iconSize = Double.parseDouble(coin.extra("iconPx", "20")) / 160.0;
        double iconX = coin.x() - coin.width() / 2 + 0.06 + iconSize / 2;
        double textX = iconX + iconSize / 2 + 0.04;
        double room = chance.x() - chance.width() / 2 - 0.03 - textX;
        board.glyph(s, iconX, coin.y(), coin.extra("iconBild", ""), null);
        board.line(s, textX, coin.y(), Component.text(coinText).color(noCoins ? RED : GOLD), coinText, FONT,
                fit(board, coinText, coin.px(), room), Align.LEFT);
        String chanceText = view.failPercent() + " %";
        board.line(s, chance.x(), chance.y(), Component.text(chanceText).color(GREEN), chanceText, FONT, chance.px(), Align.CENTER);
        // Knopf: gesperrt ohne Klick, sonst ein Klickbereich ueber der Grafik
        String buttonGlyph = button.images().get(locked ? "gesperrt" : "haupt");
        if (buttonGlyph != null) {
            if (locked) {
                board.glyph(s, button.x(), button.y(), buttonGlyph, null);
            } else {
                board.button(s, button.id(), button.x(), button.y(), button.width(), button.height(), "verzaubern", buttonGlyph,
                        button.images().get("hover"));
            }
        }
        String label = locked ? button.extra("gesperrtText", "Gesperrt") : button.text();
        board.line(s, button.x(), button.y(), Component.text(label).color(locked ? LOCKED : BUTTON), label, FONT, button.px(), Align.CENTER);
        if (!locked && buttonGlyph == null) {
            board.region(s, button.id(), button.x(), button.y(), button.width(), button.height(), "verzaubern");
        }
    }

    // Groesste Schrifthoehe (mindestens 10), bei der der Text in max Bloecke passt
    private static int fit(Board board, String plain, int px, double max) {
        int size = px;
        while (size > 10 && board.measure(plain, FONT, size) > max) {
            size--;
        }
        return size;
    }

    // Kuerzt mit Auslassungspunkten, wenn es auch in der kleinsten Schrift nicht passt
    private static String clip(Board board, String plain, int px, double max) {
        String text = plain;
        while (text.length() > 2 && board.measure(text, FONT, px) > max) {
            text = text.substring(0, text.length() - 2).stripTrailing() + "…";
        }
        return text;
    }

    // ------------------------------------------------------------ Klicks

    @Override
    public boolean click(Board board, Session s, Element e) {
        Matcher m = ROW.matcher(e.id());
        if (!m.matches() || !"knopf".equals(m.group(3))) {
            return false;
        }
        int index = Integer.parseInt(m.group(2));
        List<EnchantView> views = views(s);
        EnchantApi api = plugin.getServer().getServicesManager().load(EnchantApi.class);
        if (index >= views.size() || api == null) {
            return true;
        }
        // Kurze Sperre, bis die Seite neu geladen ist (Verzaubern laeuft ueber die Datenbank)
        long now = System.currentTimeMillis();
        if (s.data().get("enchBusy") instanceof Long until && now < until) {
            return true;
        }
        s.data().put("enchBusy", now + 1500L);
        EnchantView view = views.get(index);
        api.apply(s.player(), view.key(), view.target());
        // Das Ergebnis steht erst nach der Datenbank und dem naechsten Tick, darum zweimal neu laden
        Bukkit.getScheduler().runTaskLater(plugin, () -> reload(board, s), 15L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> reload(board, s), 60L);
        return true;
    }

    // ------------------------------------------------------------ Daten

    private static ItemStack item(Session s) {
        return s.data().get("enchItem") instanceof ItemStack stack && !stack.getType().isAir() ? stack : null;
    }

    @SuppressWarnings("unchecked")
    private static List<EnchantView> views(Session s) {
        return s.data().get("enchViews") instanceof List<?> list ? (List<EnchantView>) list : List.of();
    }

    private static long number(Session s, String key) {
        return s.data().get(key) instanceof Number n ? n.longValue() : 0;
    }
}
