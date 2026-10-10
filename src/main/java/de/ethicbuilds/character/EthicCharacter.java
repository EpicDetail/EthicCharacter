package de.ethicbuilds.character;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Layout;
import de.ethicbuilds.character.hud.Hud;
import de.ethicbuilds.character.hud.HudLayout;
import de.ethicbuilds.character.page.CharacterData;
import de.ethicbuilds.character.page.CharacterPages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class EthicCharacter extends JavaPlugin implements CommandExecutor {

    private Board board;
    private CharacterData data;

    @Override
    public void onEnable() {
        try {
            Layout layout = Layout.load("/tafel.json");
            CharacterPages pages = new CharacterPages(this);
            board = new Board(this, layout, pages);
            data = new CharacterData(this);
        } catch (Exception e) {
            getLogger().severe("tafel.json nicht lesbar, EthicCharacter wird abgeschaltet: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(board, this);
        getCommand("charakter").setExecutor(this);
        saveDefaultConfig();
        if (getConfig().getBoolean("hud.enabled", true)) {
            try {
                Hud hud = new Hud(this, HudLayout.load("/hud.json"));
                getServer().getPluginManager().registerEvents(hud, this);
                hud.start();
            } catch (Exception e) {
                getLogger().warning("HUD nicht gestartet: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        if (board != null) {
            board.closeAll();
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler haben eine Charakter-Tafel.");
            return true;
        }
        if (board.isOpen(player)) {
            board.close(player);
            return true;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Map<String, Object> values = data.load(player);
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    board.open(player, "uebersicht", values);
                }
            });
        });
        return true;
    }
}
