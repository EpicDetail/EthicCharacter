package de.ethicbuilds.character.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.ethicbuilds.survival.api.PlotApi;
import de.ethicbuilds.survival.plot.PlotEnterEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plot-Hinweis oben mittig: beim Betreten eines anderen Feldes (PlotEnterEvent aus EthicSurvival) 10 Sekunden eine Bossbar
 * (GREEN + NOTCHED_12, im Paket durchsichtig), deren Titel die Pille 140 x 23 px zeichnet: Kartenmarker, zwei Zeilen Pixelschrift
 * und bei Bedarf die Prestige-Pille. Zeichen und Masse stehen in plot-hinweis.json (tools/plot-hinweis.js im EthicLobby-Repo).
 * Der Shader im Survival-Paket (Marker-Familie 3) rueckt den Titel auf die oberste Bossbar-Zeile. Die Gruppen-Anzeige nutzt
 * BLUE + NOTCHED_20 und bleibt unberuehrt. Bedrock-Spieler (UUID mit Nullen) bekommen keinen Hinweis.
 */
public final class PlotHint implements Listener {

    private static final long SHOW_TICKS = 200L;
    private static final float VOLUME = 0.4f;
    private static final TextColor ORANGE = TextColor.color(0xF9A11E);
    private static final TextColor HINT = TextColor.color(0x9FB0C4);

    /** Ein Zeichen der Schrift und seine Laufweite in px. */
    private record Glyph(String chars, int advance, int zone) {
    }

    /** Zeichen und Masse aus plot-hinweis.json. */
    private static final class Layout {
        final Key font;
        final int titleWidth;
        final int markerR;
        final int markerG;
        final int zoneContent;
        final int ringX;
        final int textX;
        final int textRight;
        final int prestigeX;
        final int prestigeWidth;
        final int space;
        final Map<String, Glyph[]> pill = new HashMap<>();
        final Map<String, Glyph> ring = new HashMap<>();
        final Glyph prestige;
        final Map<Character, int[]> advances = new HashMap<>();
        final Map<Character, Map<String, String>> sheets = new HashMap<>();
        final String[] plus = new String[8];
        final String[] minus = new String[8];

        private Layout(JsonObject json) {
            font = Key.key(json.get("schrift").getAsString());
            titleWidth = json.get("titelBreite").getAsInt();
            JsonObject marker = json.getAsJsonObject("marker");
            markerR = marker.get("r").getAsInt();
            markerG = marker.get("g").getAsInt();
            zoneContent = marker.getAsJsonObject("zone").get("inhalt").getAsInt();
            JsonObject place = json.getAsJsonObject("lage");
            ringX = place.get("ringX").getAsInt();
            textX = place.get("textX").getAsInt();
            textRight = place.get("textRechts").getAsInt();
            prestigeX = place.get("prestigeX").getAsInt();
            space = json.get("leerzeichen").getAsInt();
            JsonObject pills = json.getAsJsonObject("pille");
            for (String name : pills.keySet()) {
                JsonObject p = pills.getAsJsonObject(name);
                pill.put(name, new Glyph[]{glyph(p.getAsJsonObject("oben")), glyph(p.getAsJsonObject("unten"))});
            }
            JsonObject rings = json.getAsJsonObject("ring");
            for (String name : rings.keySet()) {
                ring.put(name, glyph(rings.getAsJsonObject(name)));
            }
            JsonObject p = json.getAsJsonObject("prestige");
            prestige = glyph(p);
            prestigeWidth = p.get("breite").getAsInt();
            JsonObject chars = json.getAsJsonObject("zeichen");
            for (String c : chars.keySet()) {
                JsonObject o = chars.getAsJsonObject(c);
                advances.put(c.charAt(0), new int[]{o.get("lauf").getAsInt()});
                Map<String, String> sheet = new HashMap<>();
                for (String name : List.of("z1", "frei", "besetzt")) {
                    sheet.put(name, o.get(name).getAsString());
                }
                sheets.put(c.charAt(0), sheet);
            }
            JsonObject space = json.getAsJsonObject("abstand");
            JsonArray plusArray = space.getAsJsonArray("plus");
            JsonArray minusArray = space.getAsJsonArray("minus");
            for (int i = 0; i < 8; i++) {
                plus[i] = plusArray.get(i).getAsString();
                minus[i] = minusArray.get(i).getAsString();
            }
        }

        private static Glyph glyph(JsonObject o) {
            return new Glyph(o.get("zeichen").getAsString(), o.get("lauf").getAsInt(), o.has("zone") ? o.get("zone").getAsInt() : 0);
        }

        static Layout load(String resource) throws IOException {
            try (InputStream in = Layout.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException(resource + " fehlt im Plugin");
                }
                return new Layout(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        }

        TextColor marker(int zone) {
            return TextColor.color(markerR, markerG, zone);
        }

        /** Abstandszeichen fuer eine Verschiebung um delta px (negativ = zurueck). */
        String move(int delta) {
            String[] table = delta > 0 ? plus : minus;
            StringBuilder sb = new StringBuilder();
            int remaining = Math.abs(delta);
            while (remaining > 0) {
                int step = Math.min(255, remaining);
                for (int i = 0; i < 8; i++) {
                    if ((step & (1 << i)) != 0) {
                        sb.append(table[i]);
                    }
                }
                remaining -= step;
            }
            return sb.toString();
        }

        /** Breite eines Textes in px, unbekannte Zeichen zaehlen nicht. */
        int width(String text) {
            int width = 0;
            for (char c : text.toCharArray()) {
                width += c == ' ' ? space : advances.containsKey(c) ? advances.get(c)[0] : 0;
            }
            return width;
        }

        /** Text auf hoechstens max px kuerzen. */
        String fit(String text, int max) {
            StringBuilder sb = new StringBuilder();
            for (char c : text.toCharArray()) {
                sb.append(c);
                if (width(sb.toString()) > max) {
                    sb.setLength(sb.length() - 1);
                    break;
                }
            }
            return sb.toString();
        }
    }

    /** Was ein Spieler gerade sieht. */
    private static final class Shown {
        final BossBar bar;
        final BukkitTask task;

        Shown(BossBar bar, BukkitTask task) {
            this.bar = bar;
            this.task = task;
        }
    }

    /** Das Feld des letzten Hinweises (fuer /charakter plotinfo). */
    public record Last(int cellX, int cellZ, PlotApi.State state) {
    }

    private final Plugin plugin;
    private final Layout layout;
    /** nur im Server-Thread */
    private final Map<UUID, Shown> shown = new HashMap<>();
    private final Map<UUID, Last> last = new HashMap<>();

    public PlotHint(Plugin plugin) throws IOException {
        this.plugin = plugin;
        this.layout = Layout.load("/plot-hinweis.json");
    }

    /** Feld des letzten Hinweises dieses Spielers, null wenn es keinen gab. */
    public Last last(UUID player) {
        return last.get(player);
    }

    // ------------------------------------------------------------ Ereignisse

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnter(PlotEnterEvent event) {
        Player player = event.getPlayer();
        last.put(player.getUniqueId(), new Last(event.cellX(), event.cellZ(), event.state()));
        // Floodgate-UUIDs beginnen mit Nullen: Bedrock kennt Schrift und Shader nicht
        if (player.getUniqueId().getMostSignificantBits() == 0L) {
            return;
        }
        show(player, event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
        last.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ Anzeige

    private void show(Player player, PlotEnterEvent event) {
        remove(player);
        int x = event.cellX();
        int z = event.cellZ();
        boolean taken = event.state() == PlotApi.State.EIGEN || event.state() == PlotApi.State.FREMD;
        Component title;
        if (event.state() == PlotApi.State.SPAWN) {
            title = title("frei", "spawn", "Spawn-Feld", "Geschützt, kein PvP", null);
        } else if (taken) {
            // Organisation und Prestige gibt es noch nicht: Zeile 2 zeigt den Besitzer, die Prestige-Pille entfaellt
            title = title("besetzt", "besetzt", "Beansprucht · Feld " + x + ", " + z, event.ownerName(), null);
        } else {
            title = title("frei", "frei", "Freies Grundstück", "Feld " + x + ", " + z + " · beanspruchbar", null);
        }
        BossBar bar = BossBar.bossBar(title, 1.0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_12);
        player.showBossBar(bar);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> remove(player), SHOW_TICKS);
        shown.put(player.getUniqueId(), new Shown(bar, task));
        player.playSound(player.getLocation(), Sound.UI_TOAST_IN, VOLUME, 1.0f);
        if (taken) {
            // Das HUD ist nicht klickbar: die Infos gibt es ueber eine Chatzeile
            player.sendMessage(Component.text("Feld » ").color(HINT)
                    .append(Component.text("[Infos anzeigen]").color(ORANGE)
                            .hoverEvent(HoverEvent.showText(Component.text("Infos zu diesem Feld")))
                            .clickEvent(ClickEvent.runCommand("/charakter plotinfo"))));
        }
    }

    private void remove(Player player) {
        Shown old = shown.remove(player.getUniqueId());
        if (old != null) {
            old.task.cancel();
            player.hideBossBar(old.bar);
        }
    }

    // ------------------------------------------------------------ Titel

    /**
     * Der Titel: Pille, Ring, Zeile 1 (grau), Zeile 2 (Akzentfarbe) und optional die Prestige-Pille; immer genau titleWidth px breit.
     * variant = frei oder besetzt (Farbe von Pille und Zeile 2), ring = frei, besetzt oder spawn.
     */
    private Component title(String variant, String ring, String line1, String line2, String prestige) {
        Row row = new Row();
        Glyph[] pill = layout.pill.get(variant);
        row.put(pill[0]);
        row.goTo(0);
        row.put(pill[1]);
        row.goTo(layout.ringX);
        row.put(layout.ring.get(ring));
        row.goTo(layout.textX);
        row.text(layout.fit(line1, layout.textRight - layout.textX), "z1");
        row.goTo(layout.textX);
        int max2 = prestige != null ? layout.prestigeX - 4 - layout.textX : layout.textRight - layout.textX;
        row.text(layout.fit(line2, max2), variant);
        if (prestige != null) {
            row.goTo(layout.prestigeX);
            row.put(layout.prestige);
            String text = layout.fit(prestige, layout.prestigeWidth - 6);
            row.goTo(layout.prestigeX + (layout.prestigeWidth - layout.width(text)) / 2);
            row.text(text, "besetzt");
        }
        row.goTo(layout.titleWidth);
        return row.build();
    }

    /** Eine Zeile aus Zeichen und Abstaenden, die Position in px wird mitgefuehrt; Farbe = Marker der Zone. */
    private final class Row {
        private final List<Component> parts = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private int currentZone = -1;
        private int cursor;

        void goTo(int x) {
            int delta = x - cursor;
            if (delta == 0) {
                return;
            }
            // Abstandszeichen haengen am laufenden Abschnitt
            current.append(layout.move(delta));
            cursor = x;
        }

        void put(Glyph glyph) {
            append(glyph.chars(), glyph.zone());
            cursor += glyph.advance();
        }

        void text(String text, String sheet) {
            for (char c : text.toCharArray()) {
                if (c == ' ') {
                    append(" ", layout.zoneContent);
                    cursor += layout.space;
                } else if (layout.sheets.containsKey(c)) {
                    append(layout.sheets.get(c).get(sheet), layout.zoneContent);
                    cursor += layout.advances.get(c)[0];
                }
            }
        }

        private void append(String chars, int zone) {
            if (zone != currentZone && current.length() > 0) {
                flush();
            }
            currentZone = zone;
            current.append(chars);
        }

        private void flush() {
            if (current.length() > 0) {
                parts.add(Component.text(current.toString()).color(layout.marker(Math.max(currentZone, 0))));
                current.setLength(0);
            }
        }

        Component build() {
            flush();
            TextComponent.Builder builder = Component.text();
            parts.forEach(builder::append);
            // Schatten aus (der Shader blendet die Marker-Schatten zusaetzlich aus)
            return builder.build().font(layout.font).shadowColor(ShadowColor.none());
        }
    }
}
