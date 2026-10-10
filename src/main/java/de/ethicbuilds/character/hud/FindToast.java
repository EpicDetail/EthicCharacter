package de.ethicbuilds.character.hud;

import com.google.gson.JsonObject;
import de.ethicbuilds.survival.group.GroupFindEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fund-Toast: ein Gruppenmitglied hat etwas Besonderes gefunden (GroupFindEvent). Jeder Empfaenger bekommt einen echten
 * Advancement-Toast aus einem kurzlebigen Fortschritt (Bukkit.getUnsafe().loadAdvancement), der nach rund 6 Sekunden
 * wieder entfernt wird. Der Hintergrund kommt aus dem Survival-Paket (EthicLobby, tools/fund-toast.js).
 * Der Toast zeigt in Minecraft nur Titel (Zeile 2) und eine feste gelbe Typzeile; Zeile 1 ("Name hat gefunden") steckt
 * deshalb im Titel selbst und wird vom Shader 9 px nach oben geschoben, die Typzeile blendet der Shader aus.
 * Ist der Fortschritt nicht moeglich, gibt es einen Aktionstext.
 */
public final class FindToast implements Listener {

    private static final String CRITERION = "fund";
    private static final long REVOKE_TICKS = 40L;
    private static final long REMOVE_TICKS = 120L;
    /** Platz fuer Text im Toast ab x = 30 bis zum Rand der Tafel */
    private static final int TEXT_MAX = 86;

    private final Plugin plugin;
    private final GroupHud.Layout layout;
    private int counter;
    /** Offene Fortschritte: Schluessel -> Spieler, die ihn bekommen haben. Nur im Server-Thread. */
    private final Map<NamespacedKey, List<UUID>> open = new HashMap<>();

    public FindToast(Plugin plugin) throws IOException {
        this.plugin = plugin;
        this.layout = GroupHud.Layout.load("/gruppe-hud.json");
    }

    @EventHandler
    public void onFind(GroupFindEvent event) {
        int id = counter++;
        String finderName = event.finder().getName();
        String detail = event.rarityLabel() + " · " + event.itemName();
        TextColor color = TextColor.color(event.colorRgb());
        // Vier Varianten: Finder / andere, Java / Bedrock. Jede wird nur angelegt, wenn sie gebraucht wird.
        Map<String, Advancement> made = new HashMap<>();
        for (UUID receiver : event.receivers()) {
            Player player = Bukkit.getPlayer(receiver);
            if (player == null) {
                continue;
            }
            boolean finder = receiver.equals(event.finder().getUniqueId());
            boolean bedrock = receiver.getMostSignificantBits() == 0L;
            String first = finder ? "Du hast gefunden" : finderName + " hat gefunden";
            String variant = (finder ? "f" : "a") + (bedrock ? "b" : "j");
            Advancement advancement = made.computeIfAbsent(variant, v -> create(id, v, first, detail, color, bedrock));
            if (advancement == null || !award(player, advancement)) {
                player.sendActionBar(Component.text(first + ": ", TextColor.color(0x9FB0C4)).append(Component.text(detail, color)));
            }
        }
        if (!made.isEmpty()) {
            List<Advancement> all = new ArrayList<>(made.values());
            all.removeIf(a -> a == null);
            Bukkit.getScheduler().runTaskLater(plugin, () -> all.forEach(this::revokeAll), REVOKE_TICKS);
            Bukkit.getScheduler().runTaskLater(plugin, () -> all.forEach(this::remove), REMOVE_TICKS);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Fortschritt vor dem Speichern des Spielers zurueckziehen
        UUID id = event.getPlayer().getUniqueId();
        for (Map.Entry<NamespacedKey, List<UUID>> e : open.entrySet()) {
            if (e.getValue().remove(id)) {
                Advancement advancement = Bukkit.getAdvancement(e.getKey());
                if (advancement != null) {
                    event.getPlayer().getAdvancementProgress(advancement).revokeCriteria(CRITERION);
                }
            }
        }
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            new ArrayList<>(open.keySet()).forEach(key -> {
                Advancement advancement = Bukkit.getAdvancement(key);
                if (advancement != null) {
                    revokeAll(advancement);
                    remove(advancement);
                }
            });
        }
    }

    // ------------------------------------------------------------ Fortschritt

    private Advancement create(int id, String variant, String first, String detail, TextColor color, boolean bedrock) {
        try {
            Component second = Component.text(fit(detail, TEXT_MAX), color);
            Component title;
            if (bedrock) {
                // Bedrock kennt Schrift und Shader nicht: nur die zweite Zeile als Titel, die erste als Beschreibung
                title = second;
            } else {
                String firstFit = fit(first, TEXT_MAX);
                int width = textWidth(firstFit);
                Component line = Component.text(firstFit).color(TextColor.color(layout.markerR, layout.markerFund, 1));
                Component back = Component.text(layout.move(-width)).font(layout.font);
                title = Component.text().append(line).append(back).append(second).build().shadowColor(ShadowColor.none());
            }
            JsonObject icon = new JsonObject();
            icon.addProperty("id", "minecraft:enchanted_book");
            JsonObject display = new JsonObject();
            display.add("icon", icon);
            GsonComponentSerializer gson = GsonComponentSerializer.gson();
            display.add("title", gson.serializeToTree(title));
            display.add("description", gson.serializeToTree(Component.text(first)));
            display.addProperty("frame", "goal");
            display.addProperty("show_toast", true);
            display.addProperty("announce_to_chat", false);
            display.addProperty("hidden", true);
            JsonObject trigger = new JsonObject();
            trigger.addProperty("trigger", "minecraft:impossible");
            JsonObject criteria = new JsonObject();
            criteria.add(CRITERION, trigger);
            JsonObject root = new JsonObject();
            root.add("display", display);
            root.add("criteria", criteria);
            NamespacedKey key = new NamespacedKey(plugin, "fund_" + id + "_" + variant);
            Advancement advancement = Bukkit.getUnsafe().loadAdvancement(key, root.toString());
            if (advancement != null) {
                open.put(key, new ArrayList<>());
            }
            return advancement;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Fund-Toast konnte nicht angelegt werden: " + e.getMessage());
            return null;
        }
    }

    private boolean award(Player player, Advancement advancement) {
        try {
            player.getAdvancementProgress(advancement).awardCriteria(CRITERION);
            List<UUID> list = open.get(advancement.getKey());
            if (list != null) {
                list.add(player.getUniqueId());
            }
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Fund-Toast konnte nicht vergeben werden: " + e.getMessage());
            return false;
        }
    }

    private void revokeAll(Advancement advancement) {
        List<UUID> list = open.get(advancement.getKey());
        if (list == null) {
            return;
        }
        for (UUID id : list) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.getAdvancementProgress(advancement).revokeCriteria(CRITERION);
            }
        }
        list.clear();
    }

    private void remove(Advancement advancement) {
        open.remove(advancement.getKey());
        try {
            Bukkit.getUnsafe().removeAdvancement(advancement.getKey());
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Fund-Toast konnte nicht entfernt werden: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ Textbreite (Standardschrift)

    /** Breite eines Textes in der Standardschrift (Vanilla, inklusive 1 px Abstand je Zeichen). Naeherung. */
    static int textWidth(String text) {
        int width = 0;
        for (char c : text.toCharArray()) {
            width += charWidth(c);
        }
        return width;
    }

    private static int charWidth(char c) {
        if ("!,.:;i|".indexOf(c) >= 0) {
            return 2;
        }
        if ("'l`".indexOf(c) >= 0) {
            return 3;
        }
        if ("It[] \"*(){}<>".indexOf(c) >= 0) {
            return 4;
        }
        if (c == 'f' || c == 'k') {
            return 5;
        }
        if (c == '@' || c == '~') {
            return 7;
        }
        // der Mittelpunkt kommt nicht aus der ASCII-Tafel; Schaetzung
        return c == '·' ? 4 : 6;
    }

    /** Text auf hoechstens max px kuerzen (mit ...), nach der Naeherung oben. */
    private static String fit(String text, int max) {
        if (textWidth(text) <= max) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (textWidth(sb.toString() + c + "...") > max) {
                break;
            }
            sb.append(c);
        }
        return sb + "...";
    }
}
