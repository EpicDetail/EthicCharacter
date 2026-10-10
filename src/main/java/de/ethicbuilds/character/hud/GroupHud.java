package de.ethicbuilds.character.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.ethicbuilds.survival.api.GroupApi;
import de.ethicbuilds.survival.group.GroupChangedEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

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
 * Gruppen-Anzeige links am Bildschirm: pro anderem Gruppenmitglied eine Bossbar (BLUE + NOTCHED_20, im Paket
 * durchsichtig), deren Titel die Pille mit Kopf, Name, Level und Lebensleiste zeichnet. Der Shader im Survival-Paket
 * (EthicLobby, hud.survival) schiebt den Titel an den linken Rand. Bedrock-Spieler bekommen eine Textzeile.
 * Die Bossbar-Zeilen haben in Minecraft 19 px Abstand, die Pillen (16 px) liegen darin mit 3 px Luft.
 */
public final class GroupHud implements Listener {

    private static final int MAX_MEMBERS = 5;
    private static final long REFRESH_TICKS = 10L;
    private static final double LIFE_MID = 0.5;
    private static final double LIFE_LOW = 0.3;
    private static final int NAME_GAP = 3;
    private static final TextColor TEXT_GOLD = TextColor.fromHexString("#FFD24A");

    /** Zeichentabelle aus gruppe-hud.json (erzeugt von tools/gruppen-hud.js im EthicLobby-Repo). Auch fuer FindToast. */
    static final class Layout {
        record Glyph(String chars, int advance) {
        }

        final Key font;
        final int titleWidth;
        final int headAdvance;
        final int markerR;
        final int markerG;
        final int markerFund;
        final int white;
        final int name;
        final int orange;
        final int green;
        final int red;
        final int head;
        final int nameX;
        final int trackX;
        final int levelEnd;
        final Glyph pill;
        final Glyph track;
        final List<Glyph> fill = new ArrayList<>();
        final Map<Character, Integer> advances = new HashMap<>();
        final String[] plus = new String[8];
        final String[] minus = new String[8];

        private Layout(JsonObject json) {
            font = Key.key(json.get("schrift").getAsString());
            titleWidth = json.get("titelBreite").getAsInt();
            headAdvance = json.get("kopfLauf").getAsInt();
            JsonObject marker = json.getAsJsonObject("marker");
            markerR = marker.get("r").getAsInt();
            markerG = marker.get("g").getAsInt();
            JsonObject colors = marker.getAsJsonObject("farbe");
            white = colors.get("weiss").getAsInt();
            name = colors.get("name").getAsInt();
            orange = colors.get("orange").getAsInt();
            green = colors.get("gruen").getAsInt();
            red = colors.get("rot").getAsInt();
            head = colors.get("kopf").getAsInt();
            markerFund = json.getAsJsonObject("fund").get("g").getAsInt();
            JsonObject place = json.getAsJsonObject("lage");
            nameX = place.get("name").getAsInt();
            trackX = place.get("spur").getAsInt();
            levelEnd = place.get("levelEnde").getAsInt();
            pill = glyph(json.getAsJsonObject("pille"));
            track = glyph(json.getAsJsonObject("spur"));
            json.getAsJsonArray("fuellung").forEach(e -> fill.add(glyph(e.getAsJsonObject())));
            json.getAsJsonObject("zeichen").entrySet().forEach(e -> advances.put(e.getKey().charAt(0), e.getValue().getAsInt()));
            JsonObject space = json.getAsJsonObject("abstand");
            JsonArray p = space.getAsJsonArray("plus");
            JsonArray m = space.getAsJsonArray("minus");
            for (int i = 0; i < 8; i++) {
                plus[i] = p.get(i).getAsString();
                minus[i] = m.get(i).getAsString();
            }
        }

        private static Glyph glyph(JsonObject o) {
            return new Glyph(o.get("zeichen").getAsString(), o.get("lauf").getAsInt());
        }

        static Layout load(String resource) throws IOException {
            try (InputStream in = Layout.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException(resource + " fehlt im Plugin");
                }
                return new Layout(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        }

        /** Marker-Farbe der Gruppen-Anzeige, b ist der Palettenindex im Shader. */
        TextColor marker(int b) {
            return TextColor.color(markerR, markerG, b);
        }

        /** Abstandszeichen fuer eine Verschiebung um delta px (negativ = zurueck), in Schritten bis 255. */
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

        /** Breite eines Textes in px (ohne den letzten Abstand), unbekannte Zeichen zaehlen nicht. */
        int width(String text) {
            int width = 0;
            for (char c : text.toCharArray()) {
                width += advances.getOrDefault(c, 0);
            }
            return Math.max(0, width - 1);
        }
    }

    /** Was ein Spieler gerade sieht: die angezeigten Mitglieder und ihre Bossbars (Bedrock: eine Textleiste). */
    private static final class State {
        final List<UUID> ids = new ArrayList<>();
        final List<BossBar> bars = new ArrayList<>();
        BossBar text;
    }

    private final Plugin plugin;
    private final Layout layout;
    /** nur im Server-Thread */
    private final Map<UUID, State> states = new HashMap<>();

    public GroupHud(Plugin plugin) throws IOException {
        this.plugin = plugin;
        this.layout = Layout.load("/gruppe-hud.json");
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, REFRESH_TICKS, REFRESH_TICKS);
    }

    // ------------------------------------------------------------ Ereignisse

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGroupChanged(GroupChangedEvent event) {
        // einen Tick spaeter: dann gilt der neue Stand der Gruppe sicher
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID id : event.members()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    refresh(player);
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ Anzeige

    private void refreshAll() {
        GroupApi api = api();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (states.containsKey(player.getUniqueId()) || (api != null && api.inGroup(player.getUniqueId()))) {
                refresh(player);
            }
        }
    }

    private void refresh(Player player) {
        GroupApi api = api();
        UUID self = player.getUniqueId();
        List<GroupApi.Member> others = new ArrayList<>();
        if (api != null) {
            for (GroupApi.Member m : api.members(self)) {
                if (!m.uuid().equals(self) && others.size() < MAX_MEMBERS) {
                    others.add(m);
                }
            }
        }
        if (others.isEmpty()) {
            clear(player);
            return;
        }
        State state = states.computeIfAbsent(self, id -> new State());
        // Floodgate-UUIDs beginnen mit Nullen: Bedrock kennt Schrift und Shader nicht und bekommt eine Textzeile
        if (self.getMostSignificantBits() == 0L) {
            showText(player, state, others);
        } else {
            showBars(player, state, others);
        }
    }

    private void clear(Player player) {
        State state = states.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.bars.forEach(player::hideBossBar);
        if (state.text != null) {
            player.hideBossBar(state.text);
        }
    }

    private void showBars(Player player, State state, List<GroupApi.Member> members) {
        List<UUID> ids = new ArrayList<>();
        members.forEach(m -> ids.add(m.uuid()));
        if (!ids.equals(state.ids)) {
            // andere Mitglieder: Leisten neu aufbauen, damit die Reihenfolge stimmt
            state.bars.forEach(player::hideBossBar);
            state.bars.clear();
            state.ids.clear();
            state.ids.addAll(ids);
            for (GroupApi.Member m : members) {
                BossBar bar = BossBar.bossBar(title(m), 1.0f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_20);
                state.bars.add(bar);
                player.showBossBar(bar);
            }
            return;
        }
        for (int i = 0; i < members.size(); i++) {
            state.bars.get(i).name(title(members.get(i)));
        }
    }

    /** Bedrock: eine Leiste, pro Mitglied "Name Lv 12 · 65 %", getrennt mit " | ". */
    private void showText(Player player, State state, List<GroupApi.Member> members) {
        StringBuilder sb = new StringBuilder();
        double lowest = 1.0;
        for (GroupApi.Member m : members) {
            double fraction = fraction(m);
            lowest = Math.min(lowest, fraction);
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(m.name()).append(" Lv ").append(m.level()).append(" · ").append(Math.round(fraction * 100)).append(" %");
        }
        Component line = Component.text(sb.toString()).color(TEXT_GOLD);
        if (state.text == null) {
            state.text = BossBar.bossBar(line, (float) lowest, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
            player.showBossBar(state.text);
        } else {
            state.text.name(line);
            state.text.progress((float) lowest);
        }
    }

    private static double fraction(GroupApi.Member m) {
        if (m.maxHealth() <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, m.health() / m.maxHealth()));
    }

    // ------------------------------------------------------------ Titel

    /** Der Titel eines Mitglieds: Pille, Leiste, Name, Level, zuletzt der Kopf; immer genau titleWidth px breit. */
    private Component title(GroupApi.Member member) {
        Row row = new Row();
        row.goTo(0);
        row.put(layout.pill, layout.white);
        double fraction = fraction(member);
        int steps = fraction <= 0 ? 0 : Math.max(1, Math.min(layout.fill.size(), (int) Math.round(fraction * layout.fill.size())));
        row.goTo(layout.trackX);
        row.put(layout.track, layout.white);
        if (steps > 0) {
            int color = fraction < LIFE_LOW ? layout.red : fraction < LIFE_MID ? layout.orange : layout.green;
            row.goTo(layout.trackX);
            row.put(layout.fill.get(steps - 1), color);
        }
        String level = "Lv " + member.level();
        int levelStart = layout.levelEnd - layout.width(level);
        row.goTo(levelStart);
        row.text(level, layout.orange);
        row.goTo(layout.nameX);
        row.text(fit(member.name(), levelStart - NAME_GAP - layout.nameX), layout.name);
        // Der Kopf kommt zuletzt (liegt oben); der Shader setzt ihn in die Pille. Er ist 8 px breit.
        row.goTo(0);
        row.head(member.uuid());
        row.goTo(layout.titleWidth);
        return row.build();
    }

    /** Name auf hoechstens max px kuerzen. */
    private String fit(String name, int max) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(c);
            if (layout.width(sb.toString()) > max) {
                sb.setLength(sb.length() - 1);
                break;
            }
        }
        return sb.toString();
    }

    /** Eine Zeile aus Zeichen und Abstaenden, die Position in px wird mitgefuehrt. */
    private final class Row {
        private final List<Component> parts = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private int currentColor = -1;
        private int cursor;

        void goTo(int x) {
            int delta = x - cursor;
            if (delta == 0) {
                return;
            }
            // Abstandszeichen tragen keine Farbe, sie haengen am laufenden Abschnitt
            current.append(layout.move(delta));
            cursor = x;
        }

        void put(Layout.Glyph glyph, int color) {
            append(glyph.chars(), color);
            cursor += glyph.advance();
        }

        void text(String text, int color) {
            for (char c : text.toCharArray()) {
                Integer advance = layout.advances.get(c);
                if (advance != null) {
                    append(String.valueOf(c), color);
                    cursor += advance;
                }
            }
        }

        void head(UUID id) {
            flush();
            parts.add(Component.object(ObjectContents.playerHead(id)).color(layout.marker(layout.head)));
            cursor += layout.headAdvance;
        }

        private void append(String chars, int color) {
            if (color != currentColor && current.length() > 0) {
                flush();
            }
            currentColor = color;
            current.append(chars);
        }

        private void flush() {
            if (current.length() > 0) {
                parts.add(Component.text(current.toString()).color(layout.marker(currentColor < 0 ? layout.white : currentColor)));
                current.setLength(0);
            }
        }

        Component build() {
            flush();
            TextComponent.Builder builder = Component.text();
            parts.forEach(builder::append);
            // Schatten aus: die Marker-Schatten blendet der Shader zusaetzlich aus
            return builder.build().font(layout.font).shadowColor(ShadowColor.none());
        }
    }

    private GroupApi api() {
        RegisteredServiceProvider<GroupApi> provider = plugin.getServer().getServicesManager().getRegistration(GroupApi.class);
        return provider != null ? provider.getProvider() : null;
    }
}
