package dev.kcbleeker.recoverymod;

import org.bukkit.entity.Player;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import dev.kcbleeker.recoverymod.RecoveryTracking.TrackedItem;

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

    // Save a list of tracked items (inventory) to file
    public void saveInventoryFile(Player player, List<TrackedItem> items) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (TrackedItem ti : items) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("item", ti.getItemData());
            entry.put("dropId", ti.getDropId() == null ? null : ti.getDropId().toString());
            serialized.add(entry);
        }
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

    // Save a list of tracked items (tracking) to file
    public void saveTrackingData(UUID playerId, List<TrackedItem> items) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (TrackedItem ti : items) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("item", ti.getItemData());
            entry.put("dropId", ti.getDropId() == null ? null : ti.getDropId().toString());
            serialized.add(entry);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("items", serialized);
        data.put("timestamp", System.currentTimeMillis());
        File file = new File(dataFolder, playerId + "-tracking.yml");
        Yaml yaml = new Yaml(dumperOptions);
        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Load a list of tracked items from a tracking file
    public List<TrackedItem> loadTrackingData(File file) {
        if (!file.exists()) return null;
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(file.toPath()));
            List<TrackedItem> items = new ArrayList<>();
            List<Map<String, Object>> serialized = (List<Map<String, Object>>) data.get("items");
            if (serialized != null) {
                for (Map<String, Object> entry : serialized) {
                    Map<String, Object> itemData = (Map<String, Object>) entry.get("item");
                    String dropIdStr = (String) entry.get("dropId");
                    UUID dropId = dropIdStr != null ? UUID.fromString(dropIdStr) : null;
                    items.add(new TrackedItem(itemData, dropId));
                }
            }
            return items;
        } catch (Exception e) {
            return null;
        }
    }

    // Load a list of tracked items from an inventory file (with migration for old format)
    public List<TrackedItem> loadInventoryData(File file) {
        if (!file.exists()) return null;
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(file.toPath()));
            List<TrackedItem> items = new ArrayList<>();
            Object raw = data.get("inventory");
            if (raw instanceof List<?>) {
                List<?> serialized = (List<?>) raw;
                if (!serialized.isEmpty() && serialized.get(0) instanceof Map && ((Map<?,?>)serialized.get(0)).containsKey("item")) {
                    // New format: list of {item, dropId}
                    for (Object o : serialized) {
                        Map<String, Object> entry = (Map<String, Object>) o;
                        Map<String, Object> itemData = (Map<String, Object>) entry.get("item");
                        String dropIdStr = (String) entry.get("dropId");
                        UUID dropId = dropIdStr != null ? UUID.fromString(dropIdStr) : null;
                        items.add(new TrackedItem(itemData, dropId));
                    }
                } else {
                    // Old format: list of item maps (or nulls)
                    for (Object o : serialized) {
                        if (o instanceof Map) {
                            Map<String, Object> itemData = (Map<String, Object>) o;
                            items.add(new TrackedItem(itemData, null));
                        }
                        // skip nulls (empty slots)
                    }
                }
            }
            return items;
        } catch (Exception e) {
            return null;
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
