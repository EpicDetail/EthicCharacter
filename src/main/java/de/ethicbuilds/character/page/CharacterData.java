package de.ethicbuilds.character.page;

import de.ethicbuilds.economy.api.EconomyApi;
import de.ethicbuilds.survival.api.AuctionApi;
import de.ethicbuilds.survival.api.LevelApi;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Die Werte einer Tafel, im Hintergrund geladen (Datenbank), danach nur noch gelesen. */
public final class CharacterData {

    public static final int DAILIES_PER_DAY = 3;

    private final Plugin plugin;

    public CharacterData(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Laedt alles fuer einen Spieler. Nicht im Hauptthread aufrufen. */
    public Map<String, Object> load(Player player) {
        UUID id = player.getUniqueId();
        Map<String, Object> data = new HashMap<>();
        data.put("name", player.getName());
        EconomyApi economy = service(EconomyApi.class);
        data.put("coins", economy != null ? economy.balance(id) : 0L);
        LevelApi levels = service(LevelApi.class);
        data.put("level", levels != null ? levels.level(id) : 1);
        data.put("xp", levels != null ? levels.xp(id) : 0L);
        data.put("xpInto", levels != null ? levels.xpIntoLevel(id) : 0L);
        data.put("xpNext", levels != null ? levels.xpForNextLevel(id) : 0L);
        AuctionApi auction = service(AuctionApi.class);
        data.put("tax", auction != null ? auction.taxRate() : 0.0);
        data.put("dailiesDone", 0);
        data.put("resetHours", hoursUntilReset());
        rank(id, data);
        return data;
    }

    // Rang und Verlauf der Rang-Pille aus LuckPerms (Meta color1 und color2, sonst orange)
    private void rank(UUID id, Map<String, Object> data) {
        String name = "Spieler";
        String first = "#F9A11E";
        String second = null;
        boolean team = false;
        try {
            User user = LuckPermsProvider.get().getUserManager().loadUser(id).join();
            name = capitalize(user.getPrimaryGroup());
            CachedMetaData meta = user.getCachedData().getMetaData();
            if (meta.getMetaValue("color1") != null) {
                first = meta.getMetaValue("color1");
            }
            second = meta.getMetaValue("color2");
            team = user.getCachedData().getPermissionData().checkPermission("group.moderator").asBoolean();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Rang nicht gelesen: " + e.getMessage());
        }
        data.put("rank", name);
        data.put("color1", first);
        data.put("color2", second != null ? second : first);
        data.put("team", team);
    }

    /** Stunden bis Mitternacht deutscher Zeit, mindestens 1. */
    public static long hoursUntilReset() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Berlin"));
        ZonedDateTime reset = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());
        return Math.max(1, (Duration.between(now, reset).toMinutes() + 59) / 60);
    }

    private static String capitalize(String text) {
        return text == null || text.isEmpty() ? "Spieler" : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private <T> T service(Class<T> type) {
        RegisteredServiceProvider<T> provider = plugin.getServer().getServicesManager().getRegistration(type);
        return provider != null ? provider.getProvider() : null;
    }
}
