package de.ethicbuilds.character.page;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Texteingabe ueber den Chat (Suche, Betraege): die naechste Chatnachricht des Spielers geht an den Empfaenger. */
public final class Prompts implements Listener {

    private final Plugin plugin;
    private final Map<UUID, Consumer<String>> waiting = new ConcurrentHashMap<>();

    public Prompts(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Fragt im Chat nach. "abbrechen" bricht ab (Empfaenger bekommt dann nichts). Der Empfaenger laeuft im Server-Thread. */
    public void ask(Player player, String question, Consumer<String> receiver) {
        waiting.put(player.getUniqueId(), receiver);
        player.sendMessage("§6Tafel §8» §7" + question + " §8(Zum Abbrechen: abbrechen)");
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Consumer<String> receiver = waiting.remove(event.getPlayer().getUniqueId());
        if (receiver == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.equalsIgnoreCase("abbrechen")) {
            event.getPlayer().sendMessage("§6Tafel §8» §7Abgebrochen.");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> receiver.accept(text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }
}
