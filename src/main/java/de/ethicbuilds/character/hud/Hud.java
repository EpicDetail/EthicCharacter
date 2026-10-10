package de.ethicbuilds.character.hud;

import de.ethicbuilds.character.hud.HudLayout.Glyph;
import de.ethicbuilds.economy.api.CoinsChangedEvent;
import de.ethicbuilds.economy.api.EconomyApi;
import de.ethicbuilds.economy.format.CoinFormat;
import de.ethicbuilds.survival.api.LevelApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HUD ueber der Hotbar im Aktionstext: links die Coins, rechts Level und Leiste, 182 px breit wie die Hotbar. Die
 * Zeichen und die Paketvariante dazu kommen aus EthicLobby (hud.survival). Bedrock-Spieler sehen eine Textzeile.
 */
public final class Hud implements Listener {

    private static final TextColor WHITE = TextColor.color(0xFFFFFF);
    private static final TextColor GOLD = TextColor.fromHexString("#FFD24A");
    private static final TextColor ORANGE = TextColor.fromHexString("#F9A11E");
    private static final TextColor GREEN = TextColor.fromHexString("#2DBE6C");
    private static final TextColor RED = TextColor.fromHexString("#E05252");
    private static final TextColor LIFE_OVER = TextColor.fromHexString("#FF9A9A");
    private static final TextColor POISON = TextColor.fromHexString("#B8C93A");
    private static final TextColor WITHER = TextColor.fromHexString("#5B4B55");
    private static final TextColor WITHER_RIM = TextColor.fromHexString("#1C1417");
    private static final TextColor ABSORPTION = TextColor.fromHexString("#FFD24A");
    private static final TextColor HUNGER_DIM = TextColor.fromHexString("#8A5A14");
    private static final long GAIN_MILLIS = 2000;
    private static final int MAX_COIN_WIDTH = 48;
    /** 1 HP = 50 Lebenspunkte */
    private static final double LP_PER_HP = 50.0;
    private static final int FLASH_TICKS = 4;
    private static final double FLASH_MIX = 0.45;
    /** Hunger unter 20 % (food / 20): orange und blinkend */
    private static final double HUNGER_LOW = 0.2;

    /** Zugewinn der letzten Sekunden: mehrere Zugaenge addieren sich, solange sie nah beieinander liegen. */
    private static final class Gain {
        long sum;
        long until;
    }

    private final Plugin plugin;
    private final HudLayout layout;
    private final Map<UUID, Long> coins = new ConcurrentHashMap<>();
    private final Map<UUID, Gain> gains = new ConcurrentHashMap<>();
    /** Ende des Schaden-Blinkens je Spieler (Millisekunden) */
    private final Map<UUID, Long> flashUntil = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    /** Lebenspunkte, Absorption und Hunger eines Spielers fuer die zweite Ebene. */
    private record Vitals(long lp, long lpMax, long absorption, int food, boolean poison, boolean wither, boolean flash, boolean blinkOn) {
    }

    public Hud(Plugin plugin, HudLayout layout) {
        this.plugin = plugin;
        this.layout = layout;
    }

    public void start() {
        Bukkit.getOnlinePlayers().forEach(this::load);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 10L, 10L);
        // Kontostand gelegentlich neu lesen, falls anderswo im Netzwerk etwas gebucht wurde
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::reload), 600L, 600L);
    }

    // ------------------------------------------------------------ Daten

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        coins.remove(event.getPlayer().getUniqueId());
        gains.remove(event.getPlayer().getUniqueId());
        flashUntil.remove(event.getPlayer().getUniqueId());
        pending.remove(event.getPlayer().getUniqueId());
    }

    // Leben und Hunger: beim Ereignis einen Tick spaeter neu zeichnen (dann gilt der neue Wert), dazu alle 10 Ticks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            flashUntil.put(player.getUniqueId(), System.currentTimeMillis() + FLASH_TICKS * 50L);
            soon(player, 1);
            // Nach dem Blinken die Anzeige ohne Overlay
            soon(player, FLASH_TICKS + 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player) {
            soon(player, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            soon(player, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player player) {
            soon(player, 1);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        flashUntil.remove(event.getPlayer().getUniqueId());
        soon(event.getPlayer(), 1);
    }

    private void soon(Player player, long ticks) {
        // mehrere Ereignisse im selben Tick fassen wir fuer den 1-Tick-Fall zusammen
        if (ticks == 1 && !pending.add(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (ticks == 1) {
                pending.remove(player.getUniqueId());
            }
            if (player.isOnline()) {
                send(player);
            }
        }, ticks);
    }

    @EventHandler
    public void onCoins(CoinsChangedEvent event) {
        Player player = Bukkit.getPlayer(event.getPlayer());
        if (player == null) {
            return;
        }
        coins.put(event.getPlayer(), event.getBalance());
        Gain gain = gains.computeIfAbsent(event.getPlayer(), id -> new Gain());
        long now = System.currentTimeMillis();
        if (now > gain.until) {
            gain.sum = 0;
        }
        gain.sum += event.getChange();
        gain.until = now + GAIN_MILLIS;
        send(player);
        // Nach dem Ablauf die Anzeige ohne Zugewinn
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                send(player);
            }
        }, GAIN_MILLIS / 50 + 2);
    }

    private void load(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            reload(player);
            Bukkit.getScheduler().runTask(plugin, () -> send(player));
        });
    }

    private void reload(Player player) {
        EconomyApi economy = service(EconomyApi.class);
        if (economy != null) {
            coins.put(player.getUniqueId(), economy.balance(player.getUniqueId()));
        }
    }

    // ------------------------------------------------------------ Anzeige

    private void refreshAll() {
        Bukkit.getOnlinePlayers().forEach(this::send);
    }

    private void send(Player player) {
        UUID id = player.getUniqueId();
        LevelApi levels = service(LevelApi.class);
        long balance = coins.getOrDefault(id, 0L);
        int level = levels != null ? levels.level(id) : 1;
        long into = levels != null ? levels.xpIntoLevel(id) : 0;
        long next = levels != null ? levels.xpForNextLevel(id) : 0;
        boolean max = level >= LevelApi.MAX_LEVEL;
        double progress = max ? 1.0 : (double) into / next;
        Gain gain = gains.get(id);
        long delta = gain != null && System.currentTimeMillis() < gain.until ? gain.sum : 0;
        // Floodgate-UUIDs beginnen mit Nullen: Bedrock kennt unsere Schrift nicht und bekommt eine Textzeile
        Vitals vitals = vitals(player);
        if (id.getMostSignificantBits() == 0L) {
            player.sendActionBar(Component.text("❤ " + CoinFormat.number(vitals.lp()) + " / " + CoinFormat.number(vitals.lpMax())
                    + "  ·  Hunger " + Math.round(vitals.food() * 100 / 20.0) + " %"
                    + "  ·  Level " + level + "  ·  " + CoinFormat.number(balance)
                    + (delta != 0 ? "  (" + (delta > 0 ? "+" : "") + CoinFormat.number(delta) + ")" : "")).color(GOLD));
            return;
        }
        player.sendActionBar(render(balance, level, progress, max, delta, vitals));
    }

    private Vitals vitals(Player player) {
        AttributeInstance maxAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxAttribute != null ? maxAttribute.getValue() : 20.0;
        long flashEnd = flashUntil.getOrDefault(player.getUniqueId(), 0L);
        // ein Wechsel pro Sekunde, das Takt-Raster (10 Ticks) trifft ihn sicher
        boolean blinkOn = (System.currentTimeMillis() / 1000) % 2 == 0;
        return new Vitals(Math.round(player.getHealth() * LP_PER_HP), Math.round(maxHealth * LP_PER_HP),
                Math.round(player.getAbsorptionAmount() * LP_PER_HP), player.getFoodLevel(),
                player.hasPotionEffect(PotionEffectType.POISON), player.hasPotionEffect(PotionEffectType.WITHER),
                System.currentTimeMillis() < flashEnd, blinkOn);
    }

    private Component render(long balance, int level, double progress, boolean max, long delta, Vitals vitals) {
        Row row = new Row();
        if (delta != 0) {
            String text = (delta > 0 ? "+" : "-") + CoinFormat.number(Math.abs(delta));
            row.goTo(Math.max(0, (layout.coinsPlate.width() - layout.width(text, false)) / 2));
            row.text(text, false, delta > 0 ? GREEN : RED);
        }
        row.goTo(layout.coinsX);
        row.put(layout.coinsPlate, WHITE);
        String number = coinText(balance);
        int content = layout.coin.advance() + 2 + layout.width(number, true);
        row.goTo(layout.coinsX + Math.max(2, (layout.coinsPlate.width() - content) / 2));
        row.put(layout.coin, WHITE);
        row.move(2);
        row.text(number, true, GOLD);

        row.goTo(layout.levelX);
        row.put(layout.levelPlate, WHITE);
        String levelText = String.valueOf(level);
        row.goTo(layout.levelX + (layout.levelPlate.width() - layout.width(levelText, true)) / 2);
        row.text(levelText, true, max ? GOLD : ORANGE);

        row.goTo(layout.trackX);
        row.put(layout.track, WHITE);
        int steps = (int) Math.round(progress * layout.fill.size());
        if (steps > 0) {
            row.goTo(layout.trackX);
            row.put(layout.fill.get(Math.min(steps, layout.fill.size()) - 1), max ? GOLD : ORANGE);
        }
        // Prozent in der Leiste, mittig: zurueckspringen und darueber setzen (weisser Text, dunkler Rand ist eingebaut)
        int percent = max ? 100 : Math.min(99, (int) Math.floor(progress * 100));
        centered(row, percent + " %", layout.xpText, layout.trackX, layout.trackWidth);
        // Zweite Ebene: zurueckspringen, die Glyphen liegen durch ihr ascent hoeher (Oberkante 52)
        life(row, vitals);
        food(row, vitals);
        row.goTo(layout.rowWidth);
        return row.build();
    }

    private static TextColor mix(TextColor color, double toWhite) {
        int r = (int) Math.round(color.red() + (255 - color.red()) * toWhite);
        int g = (int) Math.round(color.green() + (255 - color.green()) * toWhite);
        int b = (int) Math.round(color.blue() + (255 - color.blue()) * toWhite);
        return TextColor.color(r, g, b);
    }

    /** Text mittig ueber einer Leiste: auf x zurueckspringen, Text setzen (liegt vor allem, was davor gezeichnet wurde). */
    private void centered(Row row, String text, Map<Character, Glyph> table, int barX, int barWidth) {
        row.goTo(barX + Math.max(0, (barWidth - layout.width(text, table)) / 2));
        row.text(text, table, WHITE);
    }

    /** Leben links: Herz, Leiste mit Zahl darin, rote Fuellung (Gift, Wither, Blinken, Absorption, Ueberfuellung). */
    private void life(Row row, Vitals v) {
        long lpMax = Math.max(1, v.lpMax());
        long lp = Math.max(0, v.lp());
        boolean over = lp > lpMax;
        int steps = layout.lifeFill.size();

        row.goTo(layout.heartX);
        row.put(layout.heartPlate, WHITE);
        row.goTo(layout.heartX + (layout.heartPlateWidth - layout.heart.width()) / 2);
        row.put(layout.heart, RED);

        row.goTo(layout.lifeTrackX);
        row.put(layout.lifeTrack, WHITE);

        TextColor base = v.wither() ? WITHER : v.poison() ? POISON : RED;
        TextColor fill = v.flash() ? mix(base, FLASH_MIX) : base;
        int redSteps = over ? steps : stepsFor(lp, lpMax, steps);
        int goldSteps = 0;
        if (v.absorption() > 0 && redSteps < steps) {
            goldSteps = Math.min(steps, stepsFor(lp + v.absorption(), lpMax, steps));
        }
        if (goldSteps > redSteps) {
            // Gold darunter, das Rot liegt mit gerader rechter Kante davor
            row.goTo(layout.lifeTrackX);
            row.put(layout.lifeFill.get(goldSteps - 1), ABSORPTION);
        }
        if (redSteps > 0) {
            row.goTo(layout.lifeTrackX);
            row.put((goldSteps > redSteps ? layout.lifeFillSquare : layout.lifeFill).get(redSteps - 1), fill);
        }
        if (over) {
            int extra = stepsFor(Math.min(lp - lpMax, lpMax), lpMax, steps);
            row.goTo(layout.lifeTrackX);
            row.put(layout.lifeOver.get(Math.max(1, extra) - 1), v.flash() ? mix(LIFE_OVER, FLASH_MIX) : LIFE_OVER);
        }
        // Rand: Schaden weiss, Absorption gold, Ueberfuellung hellrot, Wither dunkel
        TextColor rim = v.flash() ? WHITE : v.absorption() > 0 ? ABSORPTION : over ? LIFE_OVER : v.wither() ? WITHER_RIM : null;
        if (rim != null) {
            row.goTo(layout.lifeTrackX);
            row.put(layout.lifeRim, rim);
        }
        // Zahl zuletzt: liegt ueber Fuellung und Rand
        centered(row, CoinFormat.number(lp), layout.barText, layout.lifeTrackX, layout.lifeTrackWidth);
    }

    /** Hunger rechts, gespiegelt: Spur, Fuellung von rechts nach links, Plakette mit Besteck (unter 20 % orange, blinkend). */
    private void food(Row row, Vitals v) {
        int steps = layout.foodFill.size();
        boolean low = v.food() / 20.0 < HUNGER_LOW;
        TextColor color = low ? (v.blinkOn() ? ORANGE : HUNGER_DIM) : GREEN;
        row.goTo(layout.foodTrackX);
        row.put(layout.foodTrack, WHITE);
        int count = stepsFor(v.food(), 20, steps);
        if (count > 0) {
            row.goTo(layout.foodTrackX + layout.foodTrackWidth - layout.foodStep * count);
            row.put(layout.foodFill.get(count - 1), color);
        }
        centered(row, Math.round(v.food() * 100 / 20.0f) + " %", layout.barText, layout.foodTrackX, layout.foodTrackWidth);
        row.goTo(layout.foodPlateX);
        row.put(layout.foodPlate, WHITE);
        row.goTo(layout.foodPlateX + (layout.foodPlateWidth - layout.foodIcon.width()) / 2);
        row.put(layout.foodIcon, low ? ORANGE : GREEN);
    }

    /** Anzahl Fuellstufen: gerundet, bei Rest mindestens 1, hoechstens alle. */
    private static int stepsFor(long value, long max, int steps) {
        if (value <= 0) {
            return 0;
        }
        return (int) Math.max(1, Math.min(steps, Math.round((double) value / max * steps)));
    }

    // Zahl mit Tausenderpunkt, bei sehr grossen Zahlen als 12.3M oder 456K, damit sie in die Plakette passt
    private String coinText(long balance) {
        String full = CoinFormat.number(balance);
        if (layout.width(full, true) <= MAX_COIN_WIDTH) {
            return full;
        }
        return balance >= 10_000_000 ? balance / 1_000_000 + "M" : String.format(Locale.ROOT, "%.1fM", balance / 1_000_000.0);
    }

    /** Eine Zeile aus Zeichen und Abstaenden, die Position in px wird mitgefuehrt. */
    private final class Row {
        private final List<Component> parts = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private TextColor currentColor = WHITE;
        private int cursor;

        void goTo(int x) {
            move(x - cursor);
        }

        void move(int delta) {
            if (delta == 0) {
                return;
            }
            String[] table = delta > 0 ? layout.plus : layout.minus;
            int remaining = Math.abs(delta);
            while (remaining > 0) {
                int step = Math.min(255, remaining);
                for (int i = 0; i < 8; i++) {
                    if ((step & (1 << i)) != 0) {
                        append(table[i], currentColor);
                    }
                }
                remaining -= step;
            }
            cursor += delta;
        }

        void put(Glyph glyph, TextColor color) {
            append(glyph.chars(), color);
            cursor += glyph.advance();
        }

        void text(String text, boolean large, TextColor color) {
            text(text, large ? layout.big : layout.small, color);
        }

        void text(String text, Map<Character, Glyph> table, TextColor color) {
            for (char c : text.toCharArray()) {
                if (c == ' ') {
                    move(HudLayout.SPACE);
                    continue;
                }
                Glyph g = table.get(c);
                if (g != null) {
                    put(g, color);
                }
            }
        }

        private void append(String chars, TextColor color) {
            if (!color.equals(currentColor) && current.length() > 0) {
                flush();
            }
            currentColor = color;
            current.append(chars);
        }

        private void flush() {
            if (current.length() > 0) {
                parts.add(Component.text(current.toString()).color(currentColor));
                current.setLength(0);
            }
        }

        Component build() {
            flush();
            TextComponent.Builder builder = Component.text();
            parts.forEach(builder::append);
            return builder.build().font(layout.font);
        }
    }

    private <T> T service(Class<T> type) {
        RegisteredServiceProvider<T> provider = plugin.getServer().getServicesManager().getRegistration(type);
        return provider != null ? provider.getProvider() : null;
    }
}
