package de.ethicbuilds.character.board;

import de.ethicbuilds.character.board.Layout.Element;
import de.ethicbuilds.character.board.Layout.Glyph;
import de.ethicbuilds.character.board.Layout.Slice;
import de.ethicbuilds.character.board.Layout.Tile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schwebende Welt-Tafel vor dem Spieler, nur fuer ihn sichtbar. Flaechen sind Glyphen der Schrift ethicbuilds:tafel auf
 * Text-Displays (unbeleuchtet, exakte Farben), Texte sind Text-Displays, bedient wird mit dem Fadenkreuz und Klick.
 * Die Ebenen liegen leicht hintereinander und werden so skaliert, dass sie vom Spieler aus gleich gross aussehen.
 */
public final class Board implements Listener {

    /** Inhalt und Klicks der Seiten. */
    public interface Pages {

        /** Ergaenzt eine gezeichnete Seite um Texte und Zusatzteile. */
        void draw(Board board, Session session, String page);

        /** Text eines Layout-Elements mit ersetzten Platzhaltern, null = kein Text. */
        Component content(Session session, Element element);

        boolean visible(Session session, Element element);

        /** Farbverlauf der Rang-Pille (links, rechts). */
        TextColor[] gradient(Session session);

        void click(Board board, Session session, Element element);
    }

    /** Eine geoeffnete Tafel eines Spielers. */
    public static final class Session {
        private final Player player;
        private final double eye;
        private final Location feet;
        private final float yaw;
        private final Vector forward;
        private final Vector right;
        private final Map<String, Part> parts = new LinkedHashMap<>();
        private final List<Entity> extras = new ArrayList<>();
        private final Map<String, Object> data = new HashMap<>();
        private String page;
        private Part hover;
        private long ticks;
        private long lastClick;
        private BukkitTask task;

        private Session(Player player) {
            this.player = player;
            this.eye = player.getEyeHeight();
            this.feet = player.getLocation().clone();
            this.yaw = feet.getYaw();
            double rad = Math.toRadians(yaw);
            this.forward = new Vector(-Math.sin(rad), 0, Math.cos(rad));
            this.right = new Vector(-Math.cos(rad), 0, -Math.sin(rad));
        }

        public Player player() {
            return player;
        }

        public String page() {
            return page;
        }

        public Map<String, Object> data() {
            return data;
        }
    }

    private static final class Part {
        private final Element element;
        private final List<TextDisplay> tiles = new ArrayList<>();
        private final List<Entity> labels = new ArrayList<>();
        private String shown = "normal";

        private Part(Element element) {
            this.element = element;
        }
    }

    private static final double D_BOARD = 4.0;
    private static final double D_CARD = 3.99;
    private static final double D_SHAPE = 3.985;
    private static final double D_LINE = 3.98;
    private static final double D_TEXT = 3.975;
    private static final double BOTTOM = 0.05;
    private static final double TEXT_HEIGHT = 0.225;
    private static final double TEXT_MIDDLE = 0.13125;
    private static final double PX = 0.025;
    private static final double AWAY = 10;
    private static final long MAX_TICKS = 20L * 60 * 10;
    private static final Color NONE = Color.fromARGB(0, 0, 0, 0);

    private final Plugin plugin;
    private final Layout layout;
    private final Pages pages;
    private final TextMetrics metrics = new TextMetrics();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    // Waehrend wir selbst spawnen, darf kein anderes Plugin (z. B. WorldGuard) es verhindern
    private boolean ownSpawn;

    public Board(Plugin plugin, Layout layout, Pages pages) {
        this.plugin = plugin;
        this.layout = layout;
        this.pages = pages;
    }

    public Layout layout() {
        return layout;
    }

    public Session session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public boolean isOpen(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // ------------------------------------------------------------ Oeffnen und Schliessen

    /** Oeffnet die Tafel vor dem Spieler. Die Daten gehoeren der Seite (zum Beispiel Level und Kontostand). */
    public void open(Player player, String page, Map<String, Object> data) {
        close(player);
        Session s = new Session(player);
        if (!hasRoom(s, page)) {
            player.sendMessage("§cHier ist nicht genug Platz für die Tafel. Stell dich an eine freiere Stelle.");
            return;
        }
        s.data.putAll(data);
        sessions.put(player.getUniqueId(), s);
        try {
            draw(s, page);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Tafel fuer " + player.getName() + " nicht aufgebaut: " + e);
            close(player);
            return;
        }
        s.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(s), 1L, 1L);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.4f, 1.8f);
    }

    public void close(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) {
            return;
        }
        if (s.task != null) {
            s.task.cancel();
        }
        clear(s);
    }

    public void closeAll() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            Session s = sessions.get(id);
            if (s != null) {
                close(s.player);
            }
        }
    }

    /** Zeichnet die aktuelle Seite neu, zum Beispiel nach neuen Daten. */
    public void refresh(Session s) {
        draw(s, s.page);
    }

    public void show(Session s, String page) {
        draw(s, page);
    }

    // Ist vor dem Spieler genug freier Raum (keine festen Bloecke zwischen ihm und der Tafel)?
    private boolean hasRoom(Session s, String page) {
        double middle = BOTTOM + layout.height(page) / 2;
        for (double d = 1.0; d <= D_BOARD; d += 0.5) {
            for (double r : new double[]{-layout.width(page) / 2 + 0.2, 0, layout.width(page) / 2 - 0.2}) {
                for (double h : new double[]{0.5, middle, layout.height(page) - 0.15}) {
                    if (location(s, r * d / D_BOARD, h, d).getBlock().isSolid()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------ Zeichnen

    private void clear(Session s) {
        s.parts.values().forEach(p -> {
            p.tiles.forEach(Entity::remove);
            p.labels.forEach(Entity::remove);
        });
        s.parts.clear();
        s.extras.forEach(Entity::remove);
        s.extras.clear();
        s.hover = null;
    }

    private void draw(Session s, String page) {
        clear(s);
        s.page = page;
        for (Element e : layout.page(page)) {
            if (!pages.visible(s, e)) {
                continue;
            }
            Part part = new Part(e);
            switch (e.type()) {
                case "tafel" -> shape(s, part, D_BOARD);
                case "karte" -> shape(s, part, D_CARD);
                case "knopf", "pille", "flaeche", "zeile" -> {
                    shape(s, part, D_SHAPE);
                    icon(s, part);
                    label(s, part);
                }
                case "bild", "icon", "checkbox", "item-kachel", "stufen-pips" -> shape(s, part, D_SHAPE);
                case "linie" -> {
                    if (e.fill().isEmpty() && !e.images().isEmpty()) {
                        shape(s, part, D_LINE);
                    }
                }
                case "text" -> label(s, part);
                case "kopf" -> head(s, part);
                default -> {
                }
            }
            s.parts.put(e.id(), part);
        }
        pages.draw(this, s, page);
    }

    // Grafik des Elements: je nach Zustand ein anderes Bild, Rang-Pille in eingefaerbten Streifen
    private void shape(Session s, Part part, double depth) {
        Element e = part.element;
        if (!e.slices().isEmpty()) {
            TextColor[] gradient = pages.gradient(s);
            double offset = 0;
            double left = e.x() - e.width() / 2;
            for (Slice slice : e.slices()) {
                Glyph g = layout.glyph(slice.glyph());
                if (g == null) {
                    continue;
                }
                double width = g.tiles().get(0).width();
                double t = e.slices().size() > 1 ? (double) slice.index() / (e.slices().size() - 1) : 0;
                part.tiles.addAll(tiles(s, left + offset + width / 2, e.y(), depth, g, 1, 1, mix(gradient[0], gradient[1], t)));
                offset += width;
            }
            return;
        }
        String name = e.images().getOrDefault(e.state(), e.images().get("normal"));
        Glyph g = name != null ? layout.glyph(name) : null;
        if (g == null) {
            return;
        }
        part.shown = e.images().containsKey(e.state()) ? e.state() : "normal";
        part.tiles.addAll(tiles(s, e.x(), e.y(), depth, g, 1, 1, null));
    }

    // Piktogramm links in Pillen, Flaechen und Zeilen (Name des Bildes steht im Layout als iconBild)
    private void icon(Session s, Part part) {
        Element e = part.element;
        Glyph g = layout.glyph(e.extra("iconBild", ""));
        if (g == null || g.tiles().isEmpty()) {
            return;
        }
        double size = Double.parseDouble(e.extra("iconPx", "24")) / 160.0;
        part.tiles.addAll(tiles(s, e.x() - e.width() / 2 + 0.06 + size / 2, e.y(), D_LINE, g, 1, 1, null));
    }

    private void label(Session s, Part part) {
        Element e = part.element;
        Component text = pages.content(s, e);
        if (text == null) {
            return;
        }
        TextColor color = e.color();
        if (e.type().equals("knopf")) {
            color = switch (e.state()) {
                case "gesperrt" -> TextColor.fromHexString("#6B7C90");
                case "haupt" -> TextColor.fromHexString("#142536");
                default -> TextColor.fromHexString("#E8EEF7");
            };
        }
        Key font = Key.key(e.type().equals("knopf") ? "ethicbuilds:ui" : e.font());
        // Mit Piktogramm links rutscht der Text ein Stueck nach rechts
        double shift = e.extra().containsKey("iconBild") ? Double.parseDouble(e.extra("iconPx", "24")) / 320.0 + 0.03 : 0;
        part.labels.add(text(s, e.x() + shift, e.y(), D_TEXT, text.color(color).font(font), Math.max(8, e.px())));
        if (!e.tag().isEmpty()) {
            part.labels.add(text(s, e.x() + e.width() / 2 - 0.22, e.y(), D_TEXT,
                    Component.text(e.tag()).color(TextColor.fromHexString("#6B7C90")).font(Key.key("ethicbuilds:ui_small")), 12));
        }
    }

    private void head(Session s, Part part) {
        Element e = part.element;
        double scale = e.width() / (8 * PX);
        TextDisplay display = spawn(s, point(s, e.x(), e.y() - 0.1 * scale, D_TEXT), Component.object(ObjectContents.playerHead(s.player.getUniqueId())),
                scale, 4096);
        part.labels.add(display);
    }

    /** Text in der Mitte von x, y. px ist die Hoehe im Layout (160 px = 1 Block). */
    public TextDisplay text(Session s, double x, double y, Component text, int px) {
        TextDisplay display = text(s, x, y, D_TEXT, text, px);
        s.extras.add(display);
        return display;
    }

    private TextDisplay text(Session s, double x, double y, double depth, Component text, int px) {
        double scale = (px / 160.0) / TEXT_HEIGHT;
        return spawn(s, point(s, x, y - TEXT_MIDDLE * scale, depth), text, scale, 1000);
    }

    private TextDisplay spawn(Session s, Location at, Component text, double scale, int lineWidth) {
        float k = (float) (D_TEXT / D_BOARD);
        TextDisplay display;
        ownSpawn = true;
        try {
            display = s.feet.getWorld().spawn(at, TextDisplay.class, d -> {
                prepare(d);
                d.text(text);
                d.setBillboard(Display.Billboard.FIXED);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setLineWidth(lineWidth);
                d.setShadowed(false);
                d.setBackgroundColor(NONE);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f((float) scale * k, (float) scale * k, (float) scale * k), new Quaternionf()));
            });
        } finally {
            ownSpawn = false;
        }
        s.player.showEntity(plugin, display);
        return display;
    }

    /** Gegenstand als flaches Symbol auf der Tafel, size = Kantenlaenge in Bloecken. */
    public ItemDisplay item(Session s, double x, double y, double size, ItemStack stack) {
        float k = (float) (D_TEXT / D_BOARD);
        ItemDisplay display;
        ownSpawn = true;
        try {
            display = s.feet.getWorld().spawn(point(s, x, y, D_TEXT), ItemDisplay.class, d -> {
                prepare(d);
                d.setItemStack(stack);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
                d.setBillboard(Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f((float) size * k, (float) size * k, 0.0001f), new Quaternionf()));
            });
        } finally {
            ownSpawn = false;
        }
        s.player.showEntity(plugin, display);
        s.extras.add(display);
        return display;
    }

    /** Klickbereich ohne Grafik, zum Beispiel fuer Angebote oder Zeilen einer Liste. */
    public void region(Session s, String id, double x, double y, double width, double height, String action) {
        s.parts.put(id, new Part(Element.region(id, x, y, width, height, action)));
    }

    /** Grafik aus dem Layout zur Laufzeit zeichnen (Zustaende wie Haken, Pips), tint faerbt sie ein. */
    public void glyph(Session s, double x, double y, String name, TextColor tint) {
        Glyph g = layout.glyph(name);
        if (g != null) {
            s.extras.addAll(tiles(s, x, y, D_SHAPE, g, 1, 1, tint));
        }
    }

    public enum Align {
        LEFT, CENTER, RIGHT
    }

    /** Eine Textzeile, die an x links, mittig oder rechts buendig sitzt. plain ist der reine Text zum Messen. */
    public TextDisplay line(Session s, double x, double y, Component text, String plain, String font, int px, Align align) {
        double width = measure(plain, font, px);
        double center = switch (align) {
            case LEFT -> x + width / 2;
            case RIGHT -> x - width / 2;
            default -> x;
        };
        return text(s, center, y, text.font(Key.key(font)), px);
    }

    /** Breite eines Textes in Bloecken. */
    public double measure(String plain, String font, int px) {
        return metrics.width(plain, font) * PX * (px / 160.0) / TEXT_HEIGHT;
    }

    /** Bricht Text in Zeilen, die in width Bloecke passen. */
    public List<String> wrap(String plain, String font, int px, double width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : plain.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && measure(test, font, px) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** Fortschrittslinie: Spur, darauf die Fuellung von links bis progress (0 bis 1). */
    public void bar(Session s, Element track, double progress, String fill) {
        List<TextDisplay> back = tiles(s, track.x(), track.y(), D_SHAPE, layout.helper("spur"), track.width() / 0.2, track.height() / 0.075, null);
        s.extras.addAll(back);
        double width = track.width() * Math.max(0, Math.min(1, progress));
        if (width < 0.02) {
            return;
        }
        double left = track.x() - track.width() / 2;
        s.extras.addAll(tiles(s, left + width / 2, track.y(), D_LINE, layout.helper(fill), width / 0.2, track.height() / 0.075, null));
    }

    // Alle Kacheln einer Grafik, fx und fy strecken sie (Standardgroesse der Hilfsgrafik 0,2 x 0,075 Bloecke)
    private List<TextDisplay> tiles(Session s, double cx, double cy, double depth, Glyph glyph, double fx, double fy, TextColor tint) {
        List<TextDisplay> result = new ArrayList<>();
        for (Tile t : glyph.tiles()) {
            result.add(glyph(s, cx + t.x() * fx, cy + t.y() * fy, depth, t, t.width() * fx, t.height() * fy, tint));
        }
        return result;
    }

    /**
     * Eine Kachel als Schrift-Glyphe auf einem Text-Display: wird unbeleuchtet gezeichnet, also in exakten Farben.
     */
    private TextDisplay glyph(Session s, double x, double y, double depth, Tile t, double width, double height, TextColor tint) {
        float k = (float) (depth / D_BOARD);
        float sx = (float) (width / (t.widthPx() * PX)), sy = (float) (height / (t.heightPx() * PX));
        // Der Text wird nach seiner Laufweite zentriert, die Textur beginnt aber links: um den Unterschied schieben
        double shift = t.middle() * PX * sx;
        TextDisplay display;
        ownSpawn = true;
        try {
            Component text = tint != null ? t.text().color(tint) : t.text();
            display = s.feet.getWorld().spawn(point(s, x - shift, y - height / 2, depth), TextDisplay.class, d -> {
                prepare(d);
                d.text(text);
                d.setBillboard(Display.Billboard.FIXED);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setLineWidth(4096);
                d.setShadowed(false);
                d.setBackgroundColor(NONE);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sx * k, sy * k, k), new Quaternionf()));
            });
        } finally {
            ownSpawn = false;
        }
        s.player.showEntity(plugin, display);
        return display;
    }

    private static void prepare(Entity e) {
        e.setVisibleByDefault(false);
        e.setPersistent(false);
    }

    private static TextColor mix(TextColor a, TextColor b, double t) {
        return TextColor.color((int) Math.round(a.red() + (b.red() - a.red()) * t),
                (int) Math.round(a.green() + (b.green() - a.green()) * t), (int) Math.round(a.blue() + (b.blue() - a.blue()) * t));
    }

    // ------------------------------------------------------------ Orte

    private Location point(Session s, double x, double y, double depth) {
        double k = depth / D_BOARD;
        double middle = BOTTOM + layout.height(s.page) / 2;
        return location(s, x * k, s.eye + (middle + y - s.eye) * k, depth);
    }

    private Location location(Session s, double right, double height, double depth) {
        Location l = s.feet.clone().add(s.forward.clone().multiply(depth)).add(s.right.clone().multiply(right)).add(0, height, 0);
        l.setYaw(s.yaw + 180f);
        l.setPitch(0);
        return l;
    }

    // ------------------------------------------------------------ Zielen und Klicken

    private void tick(Session s) {
        Player p = s.player;
        if (!p.isOnline() || ++s.ticks > MAX_TICKS || away(s, p.getLocation())) {
            close(p);
            return;
        }
        if (s.ticks % 2 != 0) {
            return;
        }
        Part aimed = aimed(s);
        if (aimed == s.hover) {
            return;
        }
        if (s.hover != null) {
            setState(s.hover, s.hover.element.state());
        }
        s.hover = aimed;
        if (aimed != null) {
            setState(aimed, "hover");
        }
    }

    // Anderes Bild fuer den Zustand, wenn es eines gibt (sonst bleibt es wie es ist)
    private void setState(Part part, String state) {
        String name = part.element.images().get(state);
        if (name == null || state.equals(part.shown)) {
            return;
        }
        Glyph g = layout.glyph(name);
        if (g == null || g.tiles().size() != part.tiles.size()) {
            return;
        }
        for (int i = 0; i < part.tiles.size(); i++) {
            part.tiles.get(i).text(g.tiles().get(i).text());
        }
        part.shown = state;
    }

    /** Das bedienbare Element unter dem Fadenkreuz (Blickstrahl geschnitten mit der Tafel-Ebene), das oberste gewinnt. */
    private Part aimed(Session s) {
        Location eye = s.player.getEyeLocation();
        Vector direction = eye.getDirection();
        double denominator = direction.dot(s.forward);
        if (denominator <= 1e-4) {
            return null;
        }
        Vector plane = s.feet.toVector().add(s.forward.clone().multiply(D_BOARD));
        double t = plane.clone().subtract(eye.toVector()).dot(s.forward) / denominator;
        if (t <= 0) {
            return null;
        }
        Vector hit = eye.toVector().add(direction.multiply(t)).subtract(s.feet.toVector());
        double r = hit.dot(s.right);
        double y = hit.getY() - (BOTTOM + layout.height(s.page) / 2);
        Part result = null;
        for (Part part : s.parts.values()) {
            Element e = part.element;
            if (e.clickable() && Math.abs(r - e.x()) <= e.width() / 2 && Math.abs(y - e.y()) <= e.height() / 2) {
                result = part;
            }
        }
        return result;
    }

    private void click(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.ticks - s.lastClick < 4) {
            return;
        }
        s.lastClick = s.ticks;
        Part part = aimed(s);
        if (part == null) {
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.8f, 1.5f);
        pages.click(this, s, part.element);
    }

    // Zu weit weg oder hinter der Tafel?
    private boolean away(Session s, Location l) {
        if (l == null || !l.getWorld().equals(s.feet.getWorld()) || l.distanceSquared(s.feet) > AWAY * AWAY) {
            return true;
        }
        return l.toVector().subtract(s.feet.toVector()).dot(s.forward) > D_BOARD - 0.4;
    }

    // ------------------------------------------------------------ Ereignisse

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING && isOpen(event.getPlayer())) {
            click(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (isOpen(event.getPlayer())) {
            event.setCancelled(true);
            click(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (isOpen(event.getPlayer())) {
            event.setCancelled(true);
            click(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityInteractAt(PlayerInteractAtEntityEvent event) {
        if (isOpen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && isOpen(p)) {
            event.setCancelled(true);
            click(p);
        }
    }

    // Schleichen schliesst die Tafel
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && isOpen(event.getPlayer())) {
            close(event.getPlayer());
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null && away(s, event.getTo())) {
            close(event.getPlayer());
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        close(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        close(event.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        close(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(EntitySpawnEvent event) {
        if (ownSpawn) {
            event.setCancelled(false);
        }
    }
}
