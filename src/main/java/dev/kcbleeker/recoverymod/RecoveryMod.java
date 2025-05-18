package dev.kcbleeker.recoverymod;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.java.JavaPlugin;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class RecoveryMod extends JavaPlugin implements Listener {
    private File dataFolder;
    private RecoveryPersistence persistence;
    private RecoveryCommandHandler commandHandler;
    private DumperOptions dumperOptions;
    private int fileRetentionDays = 30;
    // Tracking maps
    private final Map<UUID, Set<UUID>> trackedDrops = new HashMap<>();
    private final Map<UUID, Map<Integer, UUID>> slotToDropId = new HashMap<>();
    // Debounce map: player UUID -> scheduled save task
    private final Map<UUID, BukkitRunnable> debounceTasks = new HashMap<>();

    @Override
    public void onEnable() {
        dataFolder = new File(getDataFolder(), "recoveries");
        if (!dataFolder.exists()) dataFolder.mkdirs();
        dumperOptions = getDumperOptions();
        persistence = new RecoveryPersistence(dataFolder, dumperOptions);
        commandHandler = new RecoveryCommandHandler(persistence, dataFolder, trackedDrops, slotToDropId);
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        // Ensure config.yml exists with default if missing
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write("retentionDays: 30\n");
            } catch (IOException e) {
                getLogger().warning("Failed to create default config.yml");
            }
        }
        // Load config for retention days if present
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(Files.newInputStream(configFile.toPath()));
            if (config != null && config.containsKey("retentionDays")) {
                fileRetentionDays = (int) config.get("retentionDays");
            }
        } catch (Exception ignored) {}
        // Cleanup old recovery and tracking files
        long cutoff = System.currentTimeMillis() - (fileRetentionDays * 24L * 60 * 60 * 1000);
        File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                try {
                    Map<String, Object> data = persistence.loadYaml(file);
                    Object tsObj = data != null ? data.get("timestamp") : null;
                    long ts = 0;
                    if (tsObj instanceof Number) ts = ((Number) tsObj).longValue();
                    else if (tsObj != null) ts = Long.parseLong(tsObj.toString());
                    if (ts > 0 && ts < cutoff) file.delete();
                } catch (Exception ignored) {}
            }
        }
        // Load tracking for all players with tracking files
        files = dataFolder.listFiles((dir, name) -> name.endsWith("-tracking.yml"));
        if (files != null) {
            for (File file : files) {
                String uuidStr = file.getName().replace("-tracking.yml", "");
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    loadTrackingData(uuid);
                } catch (Exception ignored) {}
            }
        }
        getLogger().info("RecoveryMod enabled!");
    }

    @Override
    public void onDisable() {
        // Save all tracking data on disable
        for (UUID uuid : trackedDrops.keySet()) {
            saveTrackingData(uuid);
        }
        // Cancel all debounce tasks
        for (BukkitRunnable task : debounceTasks.values()) {
            task.cancel();
        }
        debounceTasks.clear();
        getLogger().info("RecoveryMod disabled!");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        ItemStack[] contents = player.getInventory().getContents();
        List<Map<String, Object>> serialized = new ArrayList<>();
        Map<Integer, UUID> slotMap = new HashMap<>();
        Set<UUID> dropIds = new HashSet<>();
        int slot = 0;
        for (ItemStack item : contents) {
            if (item != null) {
                Map<String, Object> serializedItem = item.serialize();
                // Always add the type explicitly for clarity
                serializedItem.put("type", item.getType().name());
                serialized.add(serializedItem);
                for (Item drop : event.getEntity().getWorld().getEntitiesByClass(Item.class)) {
                    if (drop.getLocation().distance(player.getLocation()) < 2.5 && drop.getPickupDelay() != 0 && !dropIds.contains(drop.getUniqueId())) {
                        slotMap.put(slot, drop.getUniqueId());
                        dropIds.add(drop.getUniqueId());
                        break;
                    }
                }
            } else {
                serialized.add(null);
            }
            slot++;
        }
        persistence.saveInventoryFile(player, serialized);
        if (!dropIds.isEmpty()) {
            trackedDrops.put(player.getUniqueId(), dropIds);
            slotToDropId.put(player.getUniqueId(), slotMap);
            scheduleTrackingSave(player.getUniqueId());
        }
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        UUID itemId = event.getEntity().getUniqueId();
        trackedDrops.forEach((playerId, dropSet) -> {
            if (dropSet.remove(itemId)) scheduleTrackingSave(playerId);
        });
        trackedDrops.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        UUID itemId = event.getItem().getUniqueId();
        trackedDrops.forEach((playerId, dropSet) -> {
            if (dropSet.remove(itemId)) scheduleTrackingSave(playerId);
        });
        trackedDrops.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("recover")) {
            if (!sender.isOp()) {
                sender.sendMessage("You must be OP to use this command.");
                return true;
            }
            return commandHandler.handleRecoverCommand(sender, args);
        }
        return false;
    }

    private void scheduleTrackingSave(UUID playerId) {
        BukkitRunnable existing = debounceTasks.remove(playerId);
        if (existing != null) existing.cancel();
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                saveTrackingData(playerId);
                debounceTasks.remove(playerId);
            }
        };
        task.runTaskLater(this, 40L);
        debounceTasks.put(playerId, task);
    }

    private void saveTrackingData(UUID playerId) {
        Set<UUID> drops = trackedDrops.getOrDefault(playerId, Collections.emptySet());
        Map<Integer, UUID> slotMap = slotToDropId.getOrDefault(playerId, Collections.emptyMap());
        persistence.saveTrackingData(playerId, drops, slotMap);
    }

    private void loadTrackingData(UUID playerId) {
        File file = new File(dataFolder, playerId + "-tracking.yml");
        Map<String, Object> data = persistence.loadYaml(file);
        if (data == null) return;
        Set<UUID> drops = new HashSet<>();
        Object dropsObj = data.get("drops");
        if (dropsObj instanceof Collection<?>) {
            for (Object o : (Collection<?>) dropsObj) {
                if (o != null) drops.add(UUID.fromString(o.toString()));
            }
        }
        Map<Integer, UUID> slotMap = new HashMap<>();
        Object slotMapObj = data.get("slotMap");
        if (slotMapObj instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) slotMapObj).entrySet()) {
                slotMap.put(Integer.parseInt(entry.getKey().toString()), UUID.fromString(entry.getValue().toString()));
            }
        }
        trackedDrops.put(playerId, drops);
        slotToDropId.put(playerId, slotMap);
    }

    private DumperOptions getDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return options;
    }
}
