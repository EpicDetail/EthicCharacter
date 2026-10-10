package de.ethicbuilds.character.page.wiki;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Zeigt beim Betreten das Willkommensfenster (Bedrock: drei Zeilen im Chat). */
public final class WelcomeListener implements Listener {

    private final Plugin plugin;
    private final WelcomeStore store;
    private final Predicate<Player> boardOpen;
    private final BiConsumer<Player, String> opener;

    /** boardOpen: hat der Spieler gerade ein Tafelfenster offen? opener: oeffnet eine Seite (EthicCharacter#openPage). */
    public WelcomeListener(Plugin plugin, WelcomeStore store, Predicate<Player> boardOpen, BiConsumer<Player, String> opener) {
        this.plugin = plugin;
        this.store = store;
        this.boardOpen = boardOpen;
        this.opener = opener;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("welcome.enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        boolean bedrock = player.getUniqueId().getMostSignificantBits() == 0L;
        if (!bedrock && store.never(player.getUniqueId())) {
            return;
        }
        long delay = Math.max(0, plugin.getConfig().getInt("welcome.delay-seconds", 4)) * 20L;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (bedrock) {
                player.sendMessage("§6Willkommen bei EthicBuilds, " + player.getName() + "!");
                player.sendMessage("§7Mit §f/charakter §7erreichst du deinen Charakter mit Coins, Level und Dailies.");
                player.sendMessage("§7Wie alles funktioniert, steht im Wiki unter §f/charakter§7.");
            } else if (!boardOpen.test(player)) {
                opener.accept(player, "willkommen");
            }
        }, delay);
    }
}
