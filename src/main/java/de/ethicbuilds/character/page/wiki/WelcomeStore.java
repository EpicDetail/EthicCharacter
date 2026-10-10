package de.ethicbuilds.character.page.wiki;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Wer das Willkommensfenster nie wieder sehen will (plugins/EthicCharacter/willkommen.yml). */
public final class WelcomeStore {

    private final Plugin plugin;
    private final File file;
    private final Set<UUID> never = ConcurrentHashMap.newKeySet();

    public WelcomeStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "willkommen.yml");
        if (file.isFile()) {
            for (String id : YamlConfiguration.loadConfiguration(file).getStringList("nie")) {
                try {
                    never.add(UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                    // Kaputter Eintrag, wird beim naechsten Speichern verworfen
                }
            }
        }
    }

    public boolean never(UUID id) {
        return never.contains(id);
    }

    /** Merkt sich den Spieler und schreibt die Datei. */
    public synchronized void add(UUID id) {
        if (!never.add(id)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> ids = never.stream().map(UUID::toString).sorted().toList();
        yaml.set("nie", ids);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("willkommen.yml nicht gespeichert: " + e.getMessage());
        }
    }
}
