package de.ethicbuilds.character;

import de.ethicbuilds.character.board.Board;
import de.ethicbuilds.character.board.Layout;
import de.ethicbuilds.character.hud.Hud;
import de.ethicbuilds.character.hud.HudLayout;
import de.ethicbuilds.character.hud.PlotHint;
import de.ethicbuilds.character.page.CharacterData;
import de.ethicbuilds.character.page.CharacterPages;
import de.ethicbuilds.character.page.Prompts;
import de.ethicbuilds.character.page.plot.PlotInfoPage;
import de.ethicbuilds.character.page.plot.PlotsPage;
import de.ethicbuilds.survival.api.PlotApi;
import de.ethicbuilds.survival.api.BoardApi;
import org.bukkit.plugin.RegisteredServiceProvider;
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
    private PlotInfoPage plotInfo;
    private PlotHint plotHint;

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
                plotHint = new PlotHint(this);
                getServer().getPluginManager().registerEvents(plotHint, this);
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
        if (args.length == 2 && args[0].equalsIgnoreCase("scale")) {
            if (!player.isOp() && !player.hasPermission("group.manager") && !player.hasPermission("group.owner")) {
                player.sendMessage("§cDafür fehlt dir die Berechtigung.");
                return true;
            }
            try {
                double value = Double.parseDouble(args[1].replace(',', '.'));
                board.scale(value);
                getConfig().set("board.scale", board.scale());
                saveConfig();
                player.sendMessage("§6Tafel §8» §7Größe auf §6" + board.scale() + " §7gesetzt. Öffne die Tafel neu.");
            } catch (NumberFormatException e) {
                player.sendMessage("§cNutzung: /charakter scale 0.5");
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("plotinfo")) {
            openPlotInfo(player);
            return true;
        }
        if (board.isOpen(player)) {
            board.close(player);
            return true;
        }
        if (!openPage(player, "uebersicht")) {
            player.sendMessage("§cHier ist kein Platz für die Tafel (oder du spielst mit Bedrock). Stell dich an eine freiere Stelle.");
        }
        return true;
    }

    // /charakter plotinfo: Info-Tafel des Feldes, auf dem der Spieler steht, sonst des Feldes aus dem letzten Hinweis
    private void openPlotInfo(Player player) {
        RegisteredServiceProvider<PlotApi> provider = getServer().getServicesManager().getRegistration(PlotApi.class);
        if (provider == null) {
            player.sendMessage("§6Feld §8» §cDie Plotwelt ist hier nicht verfügbar.");
            return;
        }
        PlotApi api = provider.getProvider();
        PlotApi.PlotView view = api.current(player);
        if (view == null || view.state() == PlotApi.State.FREI || view.state() == PlotApi.State.SPAWN) {
            PlotHint.Last hint = plotHint != null ? plotHint.last(player.getUniqueId()) : null;
            PlotApi.PlotView old = hint != null ? api.at(player.getWorld(), hint.cellX(), hint.cellZ()) : null;
            view = old != null && (old.state() == PlotApi.State.FREMD || old.state() == PlotApi.State.EIGEN) ? old : null;
        }
        if (view == null) {
            player.sendMessage("§6Feld §8» §cDieses Feld ist nicht beansprucht.");
            return;
        }
        plotInfo.request(player, view.cellX(), view.cellZ());
        if (!openPage(player, "plot_info")) {
            player.sendMessage("§cHier ist kein Platz für die Tafel (oder du spielst mit Bedrock). Stell dich an eine freiere Stelle.");
        }
    }

    /** Oeffnet eine Seite der Tafel (Bedrock hat keine Tafel). */
    public boolean openPage(Player player, String page) {
        if (player.getUniqueId().getMostSignificantBits() == 0L || !board.layout().hasPage(page) || !board.fits(player, page)) {
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
        pages.register(new de.ethicbuilds.character.page.trader.HaendlerPage(this));
        plotInfo = new PlotInfoPage(this);
        pages.register(new PlotsPage(this, prompts, plotInfo));
        pages.register(plotInfo);
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
