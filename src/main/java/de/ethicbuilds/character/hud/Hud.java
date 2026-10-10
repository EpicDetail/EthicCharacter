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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final long GAIN_MILLIS = 2000;
    private static final int MAX_COIN_WIDTH = 48;

    /** Zugewinn der letzten Sekunden: mehrere Zugaenge addieren sich, solange sie nah beieinander liegen. */
    private static final class Gain {
        long sum;
        long until;
    }

    private final Plugin plugin;
    private final HudLayout layout;
    private final Map<UUID, Long> coins = new ConcurrentHashMap<>();
    private final Map<UUID, Gain> gains = new ConcurrentHashMap<>();

    public Hud(Plugin plugin, HudLayout layout) {
        this.plugin = plugin;
        this.layout = layout;
    }

    public void start() {
        Bukkit.getOnlinePlayers().forEach(this::load);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 20L, 20L);
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
        if (id.getMostSignificantBits() == 0L) {
            player.sendActionBar(Component.text("Level " + level + "  ·  " + CoinFormat.number(balance)
                    + (delta != 0 ? "  (" + (delta > 0 ? "+" : "") + CoinFormat.number(delta) + ")" : "")).color(GOLD));
            return;
        }
        player.sendActionBar(render(balance, level, progress, max, delta));
    }

    private Component render(long balance, int level, double progress, boolean max, long delta) {
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
        row.goTo(layout.rowWidth);
        return row.build();
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
            for (char c : text.toCharArray()) {
                Glyph g = (large ? layout.big : layout.small).get(c);
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
