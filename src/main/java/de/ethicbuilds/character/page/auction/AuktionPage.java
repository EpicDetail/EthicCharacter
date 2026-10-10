package de.ethicbuilds.character.page.auction;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Board.Align;
import de.ethicbuilds.character.board.Board.Session;
import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.page.PageHandler;
import de.ethicbuilds.character.page.Prompts;
import de.ethicbuilds.economy.api.EconomyApi;
import de.ethicbuilds.economy.format.CoinFormat;
import de.ethicbuilds.survival.api.AuctionBoardApi;
import de.ethicbuilds.survival.api.AuctionBoardApi.BookOption;
import de.ethicbuilds.survival.api.AuctionBoardApi.Category;
import de.ethicbuilds.survival.api.AuctionBoardApi.Offer;
import de.ethicbuilds.survival.api.AuctionBoardApi.Pickup;
import de.ethicbuilds.survival.api.AuctionBoardApi.SaleEntry;
import de.ethicbuilds.survival.api.AuctionBoardApi.Sort;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Das Auktionshaus auf der Tafel (Seite "auktion", Kennungen a_*). Ansehen geht ueberall, Einstellen, Abholen, Auszahlen und
 * Zurueckziehen nur beim Auktionator. Bedienung: Klick auf die Karte = Gebot auf das Mindestgebot, Klick auf den Knopf unten
 * in der Karte = Sofortkauf (ohne Sofortkauf: Bieten, bei eigenen Angeboten: Zurueckziehen, bei Abholungen: Abholen).
 * Umschalt+Klick gibt es nicht, weil Schleichen die Tafel schliesst.
 */
public final class AuktionPage implements PageHandler {

    private enum Mode {
        BROWSE, MINE, HISTORY, BOOKS
    }

    private static final String PAGE = "auktion";
    private static final String PREFIX = "§6Auktion §8» §7";
    private static final String UI = "ethicbuilds:ui";
    private static final String BOLD = "ethicbuilds:ui_bold";
    private static final TextColor TEXT = TextColor.fromHexString("#E8EEF7");
    private static final TextColor HINT = TextColor.fromHexString("#9FB0C4");
    private static final TextColor GOLD = TextColor.fromHexString("#FFD24A");
    private static final TextColor GREEN = TextColor.fromHexString("#7BD88F");
    private static final TextColor DARK = TextColor.fromHexString("#142536");
    private static final TextColor LOCKED = TextColor.fromHexString("#6B7C90");
    private static final String COIN = "icon_muenze_20_255-210-74";
    private static final String BOOK_ICON = "icon_buch_26_249-161-30";
    private static final int PER_PAGE = AuctionBoardApi.PAGE_SIZE;
    private static final Sort[] SORTS = Sort.values();
    private static final String[] SORT_NAMES = {"Preis niedrig", "Preis hoch", "Endet bald", "Neueste"};
    private static final Category[] CATEGORIES = Category.values();

    // Schluessel in session.data()
    private static final String MODE = "a_mode";
    private static final String CATEGORY = "a_kat";
    private static final String SORT = "a_sortierung";
    private static final String QUERY = "a_suche";
    private static final String PAGE_NO = "a_seite";
    private static final String PAGES = "a_seiten";
    private static final String ALL = "a_alle";
    private static final String EARNINGS = "a_einnahmen";
    private static final String SEQ = "a_folge";
    private static final String BUSY = "a_beschaeftigt";

    private final Plugin plugin;
    private final Prompts prompts;

    public AuktionPage(Plugin plugin, Prompts prompts) {
        this.plugin = plugin;
        this.prompts = prompts;
    }

    @Override
    public Set<String> pages() {
        return Set.of(PAGE);
    }

    // ------------------------------------------------------------ Laden

    @Override
    public void load(Player player, Map<String, Object> data, String page) {
        data.put(MODE, Mode.BROWSE);
        data.put(CATEGORY, Category.ALL);
        data.put(SORT, Sort.PRICE_ASC);
        data.put(QUERY, "");
        data.put(PAGE_NO, 0);
        data.put(BUSY, false);
        data.put(SEQ, 0);
        data.putAll(fetch(player.getUniqueId(), Mode.BROWSE, Category.ALL, Sort.PRICE_ASC, "", 0));
    }

    // Holt die Daten fuer den Zustand (im Hintergrund)
    private Map<String, Object> fetch(UUID id, Mode mode, Category category, Sort sort, String query, int page) {
        Map<String, Object> fresh = new HashMap<>();
        AuctionBoardApi api = api();
        EconomyApi economy = Bukkit.getServicesManager().load(EconomyApi.class);
        if (economy != null) {
            fresh.put("coins", economy.balance(id));
        }
        if (api == null) {
            fresh.put(ALL, List.of());
            fresh.put(PAGES, 1);
            fresh.put(EARNINGS, 0L);
            return fresh;
        }
        fresh.put(EARNINGS, api.earnings(id));
        switch (mode) {
            case BROWSE -> {
                AuctionBoardApi.Page found = api.browse(id, query, category, sort, page);
                fresh.put(ALL, new ArrayList<Object>(found.offers()));
                fresh.put(PAGES, found.pages());
                fresh.put(PAGE_NO, found.page());
            }
            case MINE -> {
                List<Object> all = new ArrayList<>(api.pickups(id));
                all.addAll(api.mine(id));
                put(fresh, all, page);
            }
            case HISTORY -> put(fresh, new ArrayList<Object>(api.history(id)), page);
            case BOOKS -> put(fresh, new ArrayList<Object>(api.books(id)), page);
        }
        return fresh;
    }

    private static void put(Map<String, Object> fresh, List<Object> all, int page) {
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        fresh.put(ALL, all);
        fresh.put(PAGES, pages);
        fresh.put(PAGE_NO, Math.max(0, Math.min(page, pages - 1)));
    }

    // Laedt neu im Hintergrund und zeichnet danach die Seite; die neueste Anfrage gewinnt
    private void reload(Board board, Session s) {
        Player player = s.player();
        UUID id = player.getUniqueId();
        Mode mode = mode(s);
        Category category = category(s);
        Sort sort = sort(s);
        String query = string(s, QUERY);
        int page = number(s, PAGE_NO);
        int seq = number(s, SEQ) + 1;
        s.data().put(SEQ, seq);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Object> fresh = fetch(id, mode, category, sort, query, page);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (board.session(player) == s && PAGE.equals(s.page()) && number(s, SEQ) == seq) {
                    s.data().putAll(fresh);
                    s.data().put(BUSY, false);
                    board.refresh(s);
                }
            });
        });
    }

    // ------------------------------------------------------------ Texte und Sichtbarkeit

    @Override
    public Component content(Session s, Element e) {
        switch (e.id()) {
            case "a_sort" -> {
                return Component.text("Sortieren: " + SORT_NAMES[sort(s).ordinal()]);
            }
            case "a_suche" -> {
                String query = string(s, QUERY);
                return query.isEmpty() ? null : Component.text("Suche: " + (query.length() > 40 ? query.substring(0, 40) + ".." : query))
                        .color(TEXT);
            }
            case "a_seite" -> {
                return Component.text("Seite " + (number(s, PAGE_NO) + 1) + " / " + number(s, PAGES));
            }
            case "a_einnahmen" -> {
                // Text steht in draw (passt sich der Breite an)
                return Component.empty();
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    public boolean visible(Session s, Element e) {
        String id = e.id();
        Mode mode = mode(s);
        if (id.startsWith("a_angebot_")) {
            return index(id) < cards(s).size();
        }
        if (id.equals("a_suche") || id.equals("a_sort") || id.startsWith("a_kat_")) {
            return mode == Mode.BROWSE && !overridden(s, id);
        }
        if (id.equals("a_zurueck")) {
            return number(s, PAGE_NO) > 0;
        }
        if (id.equals("a_weiter")) {
            return number(s, PAGE_NO) < number(s, PAGES) - 1;
        }
        return !overridden(s, id);
    }

    // Navigation und Kategorie zeigen den gewaehlten Eintrag: weicht der Zustand vom Layout ab, zeichnet draw den Knopf selbst
    private boolean overridden(Session s, String id) {
        if (id.startsWith("a_nav_")) {
            int active = activeNav(s);
            int i = index(id);
            return (i == active) != (i == 0);
        }
        if (id.startsWith("a_kat_") && category(s) != Category.ALL) {
            int i = index(id);
            return i == 0 || i == category(s).ordinal();
        }
        return false;
    }

    private static int activeNav(Session s) {
        return switch (mode(s)) {
            case BROWSE -> 0;
            case MINE -> 1;
            case HISTORY -> 2;
            case BOOKS -> -1;
        };
    }

    // ------------------------------------------------------------ Zeichnen

    @Override
    public void draw(Board board, Session s, String page) {
        AuctionBoardApi api = api();
        boolean near = api != null && api.isNearAuctioneer(s.player());
        for (Element e : board.layout().page(page)) {
            String id = e.id();
            if (overridden(s, id) && (id.startsWith("a_nav_") || mode(s) == Mode.BROWSE)) {
                overlayButton(board, s, e);
            }
        }
        earningsPill(board, s, near);
        leftText(board, s, near);
        List<Object> cards = cards(s);
        for (int i = 0; i < cards.size(); i++) {
            Element card = element(board, page, "a_angebot_" + i);
            if (card == null) {
                continue;
            }
            Object entry = cards.get(i);
            if (entry instanceof Offer offer) {
                offerCard(board, s, card, i, offer, near);
            } else if (entry instanceof Pickup pickup) {
                pickupCard(board, s, card, i, pickup, near);
            } else if (entry instanceof SaleEntry sale) {
                saleCard(board, s, card, sale);
            } else if (entry instanceof BookOption book) {
                bookCard(board, s, card, i, book);
            }
        }
        if (cards.isEmpty()) {
            board.text(s, 0.88, -0.28, Component.text(emptyText(s)).color(HINT).font(Key.key(UI)), 22);
        }
    }

    private String emptyText(Session s) {
        return switch (mode(s)) {
            case BROWSE -> string(s, QUERY).isEmpty() ? "Keine Angebote gefunden." : "Nichts gefunden für diese Suche.";
            case MINE -> "Du hast keine Angebote und nichts zum Abholen.";
            case HISTORY -> "Noch keine Käufe oder Verkäufe.";
            case BOOKS -> "Du hast keine Bücher.";
        };
    }

    private void overlayButton(Board board, Session s, Element e) {
        String id = e.id();
        boolean active;
        if (id.startsWith("a_nav_")) {
            active = index(id) == activeNav(s);
        } else {
            active = index(id) == category(s).ordinal() && category(s) != Category.ALL;
        }
        String glyph = id.startsWith("a_nav_") ? e.images().get(active ? "aktiv" : "normal")
                : active ? "knopf_260x42_aktiv_t" : "knopf_260x42_normal_t";
        // Aktiver Knopf ohne Hover, sonst Hover-Bild gleicher Groesse
        String hover = active ? null : id.startsWith("a_nav_") ? e.images().get("hover") : "knopf_260x42_hover_t";
        board.button(s, id, e.x(), e.y(), e.width(), e.height(), "klick", glyph, hover);
        board.text(s, e.x(), e.y(), Component.text(e.text()).color(TEXT).font(Key.key(UI)), e.px());
    }

    // Einnahmen mit Muenzsymbol; "[Abholen]" nur beim Auktionator mit Einnahmen. Der Text schrumpft, bis er in die Pille passt.
    private void earningsPill(Board board, Session s, boolean near) {
        Element e = element(board, PAGE, "a_einnahmen");
        if (e == null) {
            return;
        }
        long earnings = number(s, EARNINGS, 0L);
        String number = CoinFormat.number(earnings);
        boolean action = near && earnings > 0;
        String left = "Einnahmen";
        String right = action ? "[Abholen]" : "";
        double coin = 0.125;
        double gap = 0.06;
        int px = e.px();
        double max = e.width() - 0.14;
        double total;
        while (true) {
            total = board.measure(left, UI, px) + gap + coin + 0.03 + board.measure(number, BOLD, px)
                    + (right.isEmpty() ? 0 : gap + board.measure(right, BOLD, px));
            if (total <= max || px <= 11) {
                break;
            }
            px--;
        }
        double x = e.x() - total / 2;
        board.line(s, x, e.y(), Component.text(left).color(HINT), left, UI, px, Align.LEFT);
        x += board.measure(left, UI, px) + gap;
        board.glyph(s, x + coin / 2, e.y(), COIN, null);
        x += coin + 0.03;
        board.line(s, x, e.y(), Component.text(number).color(GOLD), number, BOLD, px, Align.LEFT);
        x += board.measure(number, BOLD, px);
        if (!right.isEmpty()) {
            board.line(s, x + gap, e.y(), Component.text(right).color(TEXT), right, BOLD, px, Align.LEFT);
        }
        board.region(s, "a_einnahmen_klick", e.x(), e.y(), e.width(), e.height(), "abholen");
    }

    // Erklaerung links unter der Navigation (statt der Kategorien) und der Hinweis zum Auktionator
    private void leftText(Board board, Session s, boolean near) {
        double x = -2.638;
        String help = switch (mode(s)) {
            case BROWSE -> "";
            case MINE -> "Deine laufenden Angebote und alles, was du beim Auktionator abholen kannst.";
            case HISTORY -> "Deine letzten Käufe und Verkäufe.";
            case BOOKS -> "Welches Buch willst du einstellen? Klicke es an.";
        };
        double y = 0.05;
        for (String line : board.wrap(help, UI, 15, 1.5)) {
            if (!line.isEmpty()) {
                board.line(s, x, y, Component.text(line).color(HINT), line, UI, 15, Align.CENTER);
                y -= 0.12;
            }
        }
        if (!near) {
            y = -1.38;
            for (String line : board.wrap("Einstellen und Abholen geht nur beim Auktionator.", UI, 14, 1.5)) {
                board.line(s, x, y, Component.text(line).color(HINT), line, UI, 14, Align.CENTER);
                y -= 0.11;
            }
        }
    }

    // ------------------------------------------------------------ Karten

    private void offerCard(Board board, Session s, Element card, int i, Offer offer, boolean near) {
        double cx = card.x();
        double cy = card.y();
        tile(board, s, cx, cy, offer.display(), offer.amount());
        bookMarks(board, s, card, offer.book(), offer.bookColor(), offer.bookInfo());
        title(board, s, cx, cy + 0.17, offer.name(), offer.bookColor());
        small(board, s, cx, cy + 0.055, offer.mine() ? "dein Angebot" : "von " + offer.seller(), HINT, 14);
        price(board, s, cx, cy - 0.065, offer.price(), offer.topIsMe() ? GREEN : GOLD);
        String second = offer.buyout() > 0 ? "Sofortkauf: " + CoinFormat.number(offer.buyout()) : "kein Sofortkauf";
        small(board, s, cx, cy - 0.19, second, HINT, 13);
        String time = (offer.topIsMe() ? "Dein Gebot" : offer.hasBid() ? "Gebot" : "Start") + " · " + duration(offer.remainingMillis());
        small(board, s, cx, cy - 0.285, time, offer.topIsMe() ? GREEN : HINT, 13);
        String label;
        boolean enabled = true;
        if (offer.mine()) {
            label = offer.hasBid() ? "Läuft (Gebote)" : "Zurückziehen";
            enabled = !offer.hasBid();
        } else if (offer.topIsMe()) {
            label = "Höchstbieter";
            enabled = false;
        } else if (offer.buyout() > 0) {
            label = "Sofort kaufen";
        } else {
            label = "Bieten";
        }
        button(board, s, card, i, label, enabled);
    }

    private void pickupCard(Board board, Session s, Element card, int i, Pickup pickup, boolean near) {
        double cx = card.x();
        double cy = card.y();
        tile(board, s, cx, cy, pickup.display(), pickup.amount());
        bookMarks(board, s, card, pickup.book(), pickup.bookColor(), pickup.bookInfo());
        title(board, s, cx, cy + 0.17, pickup.name(), pickup.bookColor());
        small(board, s, cx, cy + 0.055, note(pickup.note()), GREEN, 14);
        small(board, s, cx, cy - 0.12, pickup.book() ? "Wird gutgeschrieben" : "Liegt zur Abholung bereit", HINT, 13);
        button(board, s, card, i, "Abholen", near);
    }

    private void saleCard(Board board, Session s, Element card, SaleEntry sale) {
        double cx = card.x();
        double cy = card.y();
        board.text(s, cx, cy + 0.44, Component.text(sale.sold() ? "Verkauft" : "Gekauft").color(sale.sold() ? GOLD : GREEN)
                .font(Key.key(BOLD)), 20);
        title(board, s, cx, cy + 0.17, sale.itemName(), -1);
        small(board, s, cx, cy + 0.055, (sale.sold() ? "an " : "von ") + sale.partner(), HINT, 14);
        price(board, s, cx, cy - 0.065, sale.amount(), GOLD);
        small(board, s, cx, cy - 0.19, sale.sold() ? "Steuer: " + CoinFormat.number(sale.tax()) : "", HINT, 13);
        small(board, s, cx, cy - 0.285,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.GERMANY).format(new Date(sale.soldAt())), HINT, 13);
    }

    private void bookCard(Board board, Session s, Element card, int i, BookOption book) {
        double cx = card.x();
        double cy = card.y();
        tile(board, s, cx, cy, book.display(), book.count());
        bookMarks(board, s, card, true, book.color(), book.info());
        title(board, s, cx, cy + 0.17, book.name(), book.color());
        small(board, s, cx, cy + 0.055, "Anzahl: " + book.count(), HINT, 14);
        button(board, s, card, i, "Einstellen", true);
    }

    // Item-Kachel 62 px, bei mehr als einem Stueck mit Menge unten rechts
    private void tile(Board board, Session s, double cx, double cy, ItemStack item, int amount) {
        board.item(s, cx, cy + 0.44, 0.3875, item);
        if (amount > 1) {
            String text = "x" + amount;
            board.line(s, cx + 0.2, cy + 0.27, Component.text(text).color(TEXT), text, BOLD, 15, Align.RIGHT);
        }
    }

    // Buecher: Buch-Symbol oben links, Seltenheit oder Stufe oben rechts in der Farbe des Buchs
    private void bookMarks(Board board, Session s, Element card, boolean book, int color, String info) {
        if (!book) {
            return;
        }
        board.glyph(s, card.x() - card.width() / 2 + 0.11, card.y() + card.height() / 2 - 0.11, BOOK_ICON, null);
        if (!info.isEmpty()) {
            int px = fitPx(board, info, BOLD, 13, 0.36, 9);
            board.line(s, card.x() + card.width() / 2 - 0.07, card.y() + card.height() / 2 - 0.1,
                    Component.text(info).color(TextColor.color(color)), info, BOLD, px, Align.RIGHT);
        }
    }

    // Name in 18 px fett, bei Buechern in der Farbe des Buchs
    private void title(Board board, Session s, double cx, double y, String name, int color) {
        int px = fitPx(board, name, BOLD, 18, 1.12, 11);
        String shown = ellipsize(board, name, BOLD, px, 1.12);
        TextColor tint = color >= 0 ? TextColor.color(color) : TEXT;
        board.line(s, cx, y, Component.text(shown).color(tint), shown, BOLD, px, Align.CENTER);
    }

    private void small(Board board, Session s, double cx, double y, String text, TextColor color, int px) {
        if (text.isEmpty()) {
            return;
        }
        int fit = fitPx(board, text, UI, px, 1.12, 9);
        String shown = ellipsize(board, text, UI, fit, 1.12);
        board.line(s, cx, y, Component.text(shown).color(color), shown, UI, fit, Align.CENTER);
    }

    // Preis in Gold (26 px) mit Muenzsymbol davor, zusammen mittig
    private void price(Board board, Session s, double cx, double y, long amount, TextColor color) {
        String number = CoinFormat.number(amount);
        int px = fitPx(board, number, BOLD, 26, 0.9, 14);
        double coin = 0.125;
        double gap = 0.045;
        double total = coin + gap + board.measure(number, BOLD, px);
        double left = cx - total / 2;
        board.glyph(s, left + coin / 2, y, COIN, null);
        board.line(s, left + coin + gap, y, Component.text(number).color(color), number, BOLD, px, Align.LEFT);
    }

    // Knopf unten in der Karte (Layout-Grafik 140 x 42 px)
    private void button(Board board, Session s, Element card, int i, String label, boolean enabled) {
        double x = card.x();
        double y = card.y() - 0.50;
        // Gesperrt: gleiches Bild ohne Hover, Klick bleibt wie zuvor
        board.button(s, "a_kaufen_" + i, x, y, 0.875, 0.263, "knopf", enabled ? "knopf_140x42_haupt_k" : "knopf_140x42_gesperrt_k",
                enabled ? "knopf_140x42_haupt_hover_k" : null);
        int px = fitPx(board, label, UI, 16, 0.8, 11);
        board.text(s, x, y, Component.text(label).color(enabled ? DARK : LOCKED).font(Key.key(UI)), px);
    }

    private static int fitPx(Board board, String text, String font, int px, double max, int min) {
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

    // ------------------------------------------------------------ Klicks

    @Override
    public boolean click(Board board, Session s, Element e) {
        String id = e.id();
        if (!id.startsWith("a_")) {
            return false;
        }
        Player player = s.player();
        AuctionBoardApi api = api();
        switch (id) {
            case "a_schliessen" -> board.show(s, "uebersicht");
            case "a_nav_0" -> switchMode(board, s, Mode.BROWSE);
            case "a_nav_1" -> switchMode(board, s, Mode.MINE);
            case "a_nav_2" -> switchMode(board, s, Mode.HISTORY);
            case "a_sort" -> {
                s.data().put(SORT, SORTS[(sort(s).ordinal() + 1) % SORTS.length]);
                s.data().put(PAGE_NO, 0);
                reload(board, s);
            }
            case "a_suche" -> search(board, s);
            case "a_zurueck" -> turn(board, s, -1);
            case "a_weiter" -> turn(board, s, 1);
            case "a_erstellen" -> create(board, s);
            case "a_einnahmen_klick" -> {
                if (api != null && !busy(s)) {
                    if (!api.isNearAuctioneer(player)) {
                        player.sendMessage(PREFIX + "§cDas geht nur beim Auktionator. Ansehen kannst du hier alles.");
                    } else {
                        s.data().put(BUSY, true);
                        api.payout(player, () -> reload(board, s));
                    }
                }
            }
            default -> {
                if (id.startsWith("a_kat_") && index(id) >= 0 && index(id) < CATEGORIES.length) {
                    s.data().put(CATEGORY, CATEGORIES[index(id)]);
                    s.data().put(PAGE_NO, 0);
                    reload(board, s);
                } else if (index(id) < 0) {
                    return true;
                } else if (id.startsWith("a_kaufen_")) {
                    card(board, s, index(id), true);
                } else if (id.startsWith("a_angebot_")) {
                    card(board, s, index(id), false);
                }
            }
        }
        return true;
    }

    private void switchMode(Board board, Session s, Mode mode) {
        s.data().put(MODE, mode);
        s.data().put(PAGE_NO, 0);
        reload(board, s);
    }

    private void turn(Board board, Session s, int step) {
        int page = Math.max(0, Math.min(number(s, PAGES) - 1, number(s, PAGE_NO) + step));
        s.data().put(PAGE_NO, page);
        if (mode(s) == Mode.BROWSE) {
            reload(board, s);
        } else {
            board.refresh(s);
        }
    }

    private void search(Board board, Session s) {
        prompts.ask(s.player(), "Schreibe den §6Suchbegriff §7in den Chat (\"-\" löscht die Suche).", text -> {
            s.data().put(QUERY, text.equals("-") ? "" : text);
            s.data().put(PAGE_NO, 0);
            if (board.session(s.player()) == s) {
                reload(board, s);
            }
        });
    }

    // Klick auf Karte (Gebot bzw. Standardaktion) oder auf ihren Knopf (Sofortkauf bzw. Standardaktion)
    private void card(Board board, Session s, int index, boolean button) {
        List<Object> cards = cards(s);
        AuctionBoardApi api = api();
        if (api == null || index >= cards.size() || busy(s)) {
            return;
        }
        Player player = s.player();
        Runnable done = () -> reload(board, s);
        Object entry = cards.get(index);
        if (entry instanceof Offer offer) {
            if (offer.mine()) {
                if (offer.hasBid()) {
                    player.sendMessage(PREFIX + "§cEs gibt schon Gebote, das Angebot läuft weiter.");
                    return;
                }
                s.data().put(BUSY, true);
                api.cancel(player, offer.id(), done);
            } else if (offer.topIsMe()) {
                player.sendMessage(PREFIX + "§cDu bietest schon am höchsten.");
            } else if (offer.buyout() > 0 && (button || offer.minimumBid() >= offer.buyout())) {
                s.data().put(BUSY, true);
                api.buyout(player, offer.id(), done);
            } else {
                s.data().put(BUSY, true);
                api.bid(player, offer.id(), offer.minimumBid(), done);
            }
        } else if (entry instanceof Pickup pickup) {
            if (!api.isNearAuctioneer(player)) {
                player.sendMessage(PREFIX + "§cDas geht nur beim Auktionator. Ansehen kannst du hier alles.");
                return;
            }
            s.data().put(BUSY, true);
            api.pickup(player, pickup, done);
        } else if (entry instanceof BookOption book) {
            chooseBook(board, s, book);
        }
    }

    private boolean busy(Session s) {
        return Boolean.TRUE.equals(s.data().get(BUSY));
    }

    // ------------------------------------------------------------ Einstellen

    private void create(Board board, Session s) {
        Player player = s.player();
        AuctionBoardApi api = api();
        if (api == null || busy(s)) {
            return;
        }
        if (!api.isNearAuctioneer(player)) {
            player.sendMessage(PREFIX + "§cDas geht nur beim Auktionator. Ansehen kannst du hier alles.");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            openBooks(board, s);
            return;
        }
        ItemStack item = hand.clone();
        askPrice(player, "Schreibe den §6Startpreis §7in den Chat.", 1, 1, start ->
                askPrice(player, "Schreibe den §6Sofortkaufpreis §7(0 = kein Sofortkauf).", 0, start, buyout -> {
                    s.data().put(BUSY, true);
                    api.createFromHand(player, item, start, buyout, () -> afterCreate(board, s));
                }));
    }

    // Ohne Gegenstand in der Hand: Buecher vom Konto zur Auswahl
    private void openBooks(Board board, Session s) {
        Player player = s.player();
        AuctionBoardApi api = api();
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<BookOption> books = api.books(id);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (books.isEmpty()) {
                    player.sendMessage(PREFIX + "§cNimm den Gegenstand, den du verkaufen willst, in die Hand. Bücher hast du keine.");
                    return;
                }
                if (board.session(player) == s && PAGE.equals(s.page())) {
                    s.data().put(MODE, Mode.BOOKS);
                    Map<String, Object> fresh = new HashMap<>();
                    put(fresh, new ArrayList<Object>(books), 0);
                    s.data().putAll(fresh);
                    board.refresh(s);
                }
            });
        });
    }

    private void chooseBook(Board board, Session s, BookOption book) {
        Player player = s.player();
        AuctionBoardApi api = api();
        Consumer<Long> next = amount ->
                askPrice(player, "Schreibe den §6Startpreis §7in den Chat.", 1, 1, start ->
                        askPrice(player, "Schreibe den §6Sofortkaufpreis §7(0 = kein Sofortkauf).", 0, start, buyout -> {
                            s.data().put(BUSY, true);
                            api.createBook(player, book.enchant(), book.level(), amount.intValue(), start, buyout, () -> afterCreate(board, s));
                        }));
        if (book.count() > 1) {
            askPrice(player, "Schreibe die §6Anzahl §7(1 bis " + book.count() + ") in den Chat.", 1, 1, amount -> {
                if (amount > book.count()) {
                    player.sendMessage(PREFIX + "§cBitte eine Zahl von 1 bis " + book.count() + " schreiben.");
                    chooseBook(board, s, book);
                    return;
                }
                next.accept(amount);
            });
        } else {
            next.accept(1L);
        }
    }

    // Nach dem Einstellen zu "Meine Angebote" wechseln
    private void afterCreate(Board board, Session s) {
        s.data().put(MODE, Mode.MINE);
        s.data().put(PAGE_NO, 0);
        reload(board, s);
    }

    // Fragt im Chat nach einer ganzen Zahl (ab min); bei falscher Eingabe wird neu gefragt. startPrice prueft den Sofortkauf.
    private void askPrice(Player player, String question, long min, long startPrice, Consumer<Long> receiver) {
        prompts.ask(player, question, text -> {
            long value = parse(text);
            if (value < min) {
                player.sendMessage(PREFIX + "§cBitte nur eine ganze Zahl schreiben" + (min > 0 ? " (mindestens " + min + ")." : "."));
                askPrice(player, question, min, startPrice, receiver);
                return;
            }
            if (min == 0 && value != 0 && value < startPrice) {
                player.sendMessage(PREFIX + "§cDer Sofortkauf muss mindestens so hoch sein wie der Startpreis.");
                askPrice(player, question, min, startPrice, receiver);
                return;
            }
            receiver.accept(value);
        });
    }

    private static long parse(String text) {
        try {
            return Long.parseLong(text.replace(".", "").replace(",", "").replace(" ", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------ Hilfen

    private AuctionBoardApi api() {
        return Bukkit.getServicesManager().load(AuctionBoardApi.class);
    }

    private static Element element(Board board, String page, String id) {
        for (Element e : board.layout().page(page)) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    // Die Eintraege der aktuellen Seite: beim Stoebern kommt die Seite fertig vom Server, sonst wird die Liste geschnitten
    @SuppressWarnings("unchecked")
    private static List<Object> cards(Session s) {
        Object all = s.data().get(ALL);
        if (!(all instanceof List<?> list)) {
            return List.of();
        }
        if (mode(s) == Mode.BROWSE) {
            return (List<Object>) list;
        }
        int from = Math.min(list.size(), number(s, PAGE_NO) * PER_PAGE);
        return (List<Object>) list.subList(from, Math.min(list.size(), from + PER_PAGE));
    }

    private static Mode mode(Session s) {
        return s.data().get(MODE) instanceof Mode m ? m : Mode.BROWSE;
    }

    private static Category category(Session s) {
        return s.data().get(CATEGORY) instanceof Category c ? c : Category.ALL;
    }

    private static Sort sort(Session s) {
        return s.data().get(SORT) instanceof Sort o ? o : Sort.PRICE_ASC;
    }

    private static int index(String id) {
        try {
            return Integer.parseInt(id.substring(id.lastIndexOf('_') + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int number(Session s, String key) {
        return s.data().get(key) instanceof Number n ? n.intValue() : 0;
    }

    private static long number(Session s, String key, long fallback) {
        return s.data().get(key) instanceof Number n ? n.longValue() : fallback;
    }

    private static String string(Session s, String key) {
        return s.data().get(key) instanceof String text ? text : "";
    }

    private static String note(String note) {
        return switch (note) {
            case "Zurueckgezogen" -> "Zurückgezogen";
            default -> note;
        };
    }

    private static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        if (days > 0) {
            return days + " Tg. " + hours + " Std.";
        }
        return hours > 0 ? hours + " Std. " + (minutes % 60) + " Min." : minutes + " Min.";
    }
}
