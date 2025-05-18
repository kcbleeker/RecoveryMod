package dev.kcbleeker.recoverymod;

import org.bukkit.entity.Player;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Handles persistence of recovery and tracking data to disk.
 */
public class RecoveryPersistence {
    private final File dataFolder;
    private final DumperOptions dumperOptions;

    public RecoveryPersistence(File dataFolder, DumperOptions dumperOptions) {
        this.dataFolder = dataFolder;
        this.dumperOptions = dumperOptions;
    }

    public void saveInventoryFile(Player player, List<Map<String, Object>> serialized) {
        Map<String, Object> data = new HashMap<>();
        data.put("inventory", serialized);
        data.put("timestamp", System.currentTimeMillis());
        File file = new File(dataFolder, player.getUniqueId() + ".yml");
        Yaml yaml = new Yaml(dumperOptions);
        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveTrackingData(UUID playerId, Set<UUID> dropIds, Map<Integer, UUID> slotToDropId) {
        File file = new File(dataFolder, playerId + "-tracking.yml");
        Map<String, Object> data = new HashMap<>();
        data.put("drops", dropIds);
        data.put("slotMap", slotToDropId);
        data.put("timestamp", System.currentTimeMillis());
        Yaml yaml = new Yaml(dumperOptions);
        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Map<String, Object> loadYaml(File file) {
        if (!file.exists()) return null;
        try {
            Yaml yaml = new Yaml();
            return yaml.load(Files.newInputStream(file.toPath()));
        } catch (Exception e) {
            return null;
        }
    }
}
