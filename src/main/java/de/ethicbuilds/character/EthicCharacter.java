package de.ethicbuilds.character;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Layout;
import de.ethicbuilds.character.hud.Hud;
import de.ethicbuilds.character.hud.HudLayout;
import de.ethicbuilds.character.page.CharacterData;
import de.ethicbuilds.character.page.CharacterPages;
import de.ethicbuilds.character.page.Prompts;
import de.ethicbuilds.survival.api.BoardApi;
import org.bukkit.plugin.ServicePriority;
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
    private CharacterPages pages;
    private Prompts prompts;

    @Override
    public void onEnable() {
        try {
            Layout layout = Layout.load("/tafel.json");
            pages = new CharacterPages(this);
            prompts = new Prompts(this);
            board = new Board(this, layout, pages);
            data = new CharacterData(this);
            pages.data(data);
        } catch (Exception e) {
            getLogger().severe("tafel.json nicht lesbar, EthicCharacter wird abgeschaltet: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(board, this);
        getServer().getPluginManager().registerEvents(prompts, this);
        getServer().getServicesManager().register(BoardApi.class, this::openPage, this, ServicePriority.Normal);
        registerPages();
        getCommand("charakter").setExecutor(this);
        saveDefaultConfig();
        if (getConfig().getBoolean("hud.enabled", true)) {
            try {
                Hud hud = new Hud(this, HudLayout.load("/hud.json"));
                getServer().getPluginManager().registerEvents(hud, this);
                hud.start();
                de.ethicbuilds.character.hud.GroupHud groupHud = new de.ethicbuilds.character.hud.GroupHud(this);
                getServer().getPluginManager().registerEvents(groupHud, this);
                groupHud.start();
                getServer().getPluginManager().registerEvents(new de.ethicbuilds.character.hud.FindToast(this), this);
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
        openPage(player, "uebersicht");
        return true;
    }

    /** Oeffnet eine Seite der Tafel (Bedrock hat keine Tafel). */
    public boolean openPage(Player player, String page) {
        if (player.getUniqueId().getMostSignificantBits() == 0L || !board.layout().hasPage(page)) {
            return false;
        }
        if (board.isOpen(player)) {
            board.close(player);
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Map<String, Object> values = data.load(player);
            pages.load(player, values, page);
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    board.open(player, page, values);
                }
            });
        });
        return true;
    }

    // Seiten mit eigenem Inhalt hier anmelden
    private void registerPages() {
        pages.register(new de.ethicbuilds.character.page.log.ProtokollPage(this));
        pages.register(new de.ethicbuilds.character.page.daily.DailiesPage(this));
        pages.register(new de.ethicbuilds.character.page.transfer.TransferPage(this, prompts));
        pages.register(new de.ethicbuilds.character.page.enchant.VerzauberPage(this, data));
        pages.register(new de.ethicbuilds.character.page.auction.AuktionPage(this, prompts));
        try {
            de.ethicbuilds.character.page.wiki.WikiContent wiki = de.ethicbuilds.character.page.wiki.WikiContent.load("/wiki.json");
            pages.register(new de.ethicbuilds.character.page.wiki.WikiPage(this, prompts, board, wiki));
        } catch (Exception e) {
            getLogger().warning("Wiki nicht geladen: " + e.getMessage());
        }
        de.ethicbuilds.character.page.wiki.WelcomeStore welcome = new de.ethicbuilds.character.page.wiki.WelcomeStore(this);
        pages.register(new de.ethicbuilds.character.page.wiki.WelcomePage(board, welcome));
        getServer().getPluginManager().registerEvents(
                new de.ethicbuilds.character.page.wiki.WelcomeListener(this, welcome, board::isOpen, this::openPage), this);
    }
}
