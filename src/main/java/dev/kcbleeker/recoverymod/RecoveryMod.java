package dev.kcbleeker.recoverymod;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemMergeEvent;
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
import dev.kcbleeker.recoverymod.RecoveryTracking.TrackedItem;

public class RecoveryMod extends JavaPlugin implements Listener {
    private File dataFolder;
    private RecoveryPersistence persistence;
    private RecoveryCommandHandler commandHandler;
    private DumperOptions dumperOptions;
    private int fileRetentionDays = 30;

    // Tracking map: player UUID -> list of tracked items
    private final Map<UUID, List<TrackedItem>> trackedItems = new HashMap<>();

    // Debounce map: player UUID -> scheduled save task
    private final Map<UUID, BukkitRunnable> debounceTasks = new HashMap<>();

    // New: Map from drop UUID to player UUID for fast lookup
    private final Map<UUID, UUID> dropToPlayer = new HashMap<>();

    private DropAssignmentManager dropAssignmentManager;

    @Override
    public void onEnable() {
        setupDataFolder();
        setupPersistenceAndCommands();
        registerEvents();
        handleConfigFile();
        cleanupOldRecoveryFiles();
        loadAllPlayerTracking();
        dropAssignmentManager = new DropAssignmentManager(this, trackedItems, this::scheduleTrackingSave);
        getLogger().info("RecoveryMod enabled!");
    }

    @Override
    public void onDisable() {
        trackedItems.keySet().forEach(this::saveTrackingData);
        debounceTasks.values().forEach(BukkitRunnable::cancel);
        debounceTasks.clear();
        getLogger().info("RecoveryMod disabled!");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        List<TrackedItem> tracked = serializeInventory(player);
        persistence.saveInventoryFile(player, tracked);
        if (!tracked.isEmpty()) {
            trackedItems.put(player.getUniqueId(), tracked);
            dropAssignmentManager.scheduleDropIdAssignment(player);
        }
    }

    private List<TrackedItem> serializeInventory(Player player) {
        List<TrackedItem> tracked = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getContents();
        // Only add main inventory slots (0-35)
        for (int i = 0; i < 36 && i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null) tracked.add(createTrackedItem(item));
        }
        addItems(tracked, player.getInventory().getArmorContents());
        addOffhand(tracked, player);
        return tracked;
    }

    private void addItems(List<TrackedItem> tracked, ItemStack[] items) {
        for (ItemStack item : items) {
            if (item != null) tracked.add(createTrackedItem(item));
        }
    }

    private void addOffhand(List<TrackedItem> tracked, Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() != org.bukkit.Material.AIR)
            tracked.add(createTrackedItem(offhand));
    }

    private TrackedItem createTrackedItem(ItemStack item) {
        Map<String, Object> serializedItem = item.serialize();
        serializedItem.put("type", item.getType().name());
        return new TrackedItem(serializedItem, null);
    }

    @EventHandler
    public void onEntityRemoved(EntityRemoveEvent event) {
        if (!isTrackedItemEntity(event)) return;
        UUID itemId = event.getEntity().getUniqueId();
        UUID playerId = dropToPlayer.get(itemId);
        if (playerId != null) markDespawnedAndSave(itemId, playerId);
    }

    private boolean isTrackedItemEntity(EntityRemoveEvent event) {
        return event.getEntity() instanceof org.bukkit.entity.Item &&
               event.getCause() == EntityRemoveEvent.Cause.DESPAWN;
    }

    private void markDespawnedAndSave(UUID itemId, UUID playerId) {
        List<TrackedItem> itemList = trackedItems.get(playerId);
        if (itemList == null) return;
        for (int i = 0; i < itemList.size(); i++) {
            if (markDespawnedIfMatch(itemList, i, itemId, playerId)) {
                scheduleTrackingSave(playerId);
                break;
            }
        }
    }

    private boolean markDespawnedIfMatch(List<TrackedItem> itemList, int i, UUID itemId, UUID playerId) {
        TrackedItem ti = itemList.get(i);
        if (itemId.equals(ti.getDropId())) {
            Map<String, Object> newData = new HashMap<>(ti.getItemData());
            newData.put("_despawned", true);
            itemList.set(i, new TrackedItem(newData, null));
            return true;
        }
        return false;
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        UUID itemId = event.getItem().getUniqueId();
        UUID playerId = dropToPlayer.get(itemId);
        if (playerId != null) handleTrackedPickup(itemId, playerId);
        trackedItems.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private void handleTrackedPickup(UUID itemId, UUID playerId) {
        List<TrackedItem> itemList = trackedItems.get(playerId);
        if (itemList != null && removeTrackedItem(itemList, itemId))
            cleanupTrackedItems(playerId);
        dropToPlayer.remove(itemId);
    }

    private boolean removeTrackedItem(List<TrackedItem> itemList, UUID itemId) {
        Iterator<TrackedItem> it = itemList.iterator();
        while (it.hasNext()) {
            TrackedItem ti = it.next();
            if (itemId.equals(ti.getDropId())) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onItemMerge(ItemMergeEvent event) {
        UUID sourceId = event.getEntity().getUniqueId();
        UUID targetId = event.getTarget().getUniqueId();
        UUID playerIdSource = dropToPlayer.get(sourceId);
        UUID playerIdTarget = dropToPlayer.get(targetId);
        if (playerIdSource == null && playerIdTarget == null) return;
        UUID playerId = playerIdSource != null ? playerIdSource : playerIdTarget;
        List<TrackedItem> items = trackedItems.get(playerId);
        if (items == null) return;
        // Find tracked items for both source and target
        int sourceIdx = -1, targetIdx = -1;
        for (int i = 0; i < items.size(); i++) {
            UUID dropId = items.get(i).getDropId();
            if (dropId != null && dropId.equals(sourceId)) sourceIdx = i;
            if (dropId != null && dropId.equals(targetId)) targetIdx = i;
        }
        if (sourceIdx == -1 && targetIdx == -1) return;
        // Merge logic: combine counts, keep target, remove source
        if (sourceIdx != -1 && targetIdx != -1) {
            Map<String, Object> targetData = new HashMap<>(items.get(targetIdx).getItemData());
            Map<String, Object> sourceData = items.get(sourceIdx).getItemData();
            int targetCount = (int) targetData.getOrDefault("amount", targetData.getOrDefault("count", 1));
            int sourceCount = (int) sourceData.getOrDefault("amount", sourceData.getOrDefault("count", 1));
            int newCount = targetCount + sourceCount;
            if (targetData.containsKey("amount")) targetData.put("amount", newCount);
            else targetData.put("count", newCount);
            items.set(targetIdx, new TrackedItem(targetData, targetId));
            items.remove(sourceIdx > targetIdx ? sourceIdx : sourceIdx); // Remove source
            dropToPlayer.remove(sourceId);
        } else if (sourceIdx != -1) {
            // Only source tracked, update dropId to target
            Map<String, Object> data = new HashMap<>(items.get(sourceIdx).getItemData());
            items.set(sourceIdx, new TrackedItem(data, targetId));
            dropToPlayer.remove(sourceId);
            dropToPlayer.put(targetId, playerId);
        }
        // Save changes
        scheduleTrackingSave(playerId);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("recover")) {
            if (!sender.isOp()) {
                sender.sendMessage("You must be OP to use this command.");
                return true;
            }
            boolean result = commandHandler.handleRecoverCommand(sender, args);
            if (args.length == 1) cleanupAfterRecovery(args[0]);
            return result;
        }
        return false;
    }

    private void cleanupTrackedItems(UUID playerId) {
        List<TrackedItem> items = trackedItems.get(playerId);
        if (items != null) {
            items.removeIf(ti -> ti.getDropId() == null);
            if (items.isEmpty()) trackedItems.remove(playerId);
            scheduleTrackingSave(playerId);
        }
    }

    private void cleanupAfterRecovery(String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target != null) {
            cleanupTrackedItems(target.getUniqueId());
        }
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
        List<TrackedItem> items = trackedItems.getOrDefault(playerId, Collections.emptyList());
        persistence.saveTrackingData(playerId, items);
    }

    private void loadTrackingData(UUID playerId) {
        File file = new File(dataFolder, playerId + "-tracking.yml");
        List<TrackedItem> items = persistence.loadTrackingData(file);
        if (items != null && !items.isEmpty()) trackedItems.put(playerId, items);
    }

    private DumperOptions getDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return options;
    }

    private void handleConfig(File configFile) {
        if (!configFile.exists()) createDefaultConfig(configFile);
        loadConfig(configFile);
    }

    private void createDefaultConfig(File configFile) {
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("retentionDays: 30\n");
        } catch (IOException e) {
            getLogger().warning("Failed to create default config.yml");
        }
    }

    private void loadConfig(File configFile) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(Files.newInputStream(configFile.toPath()));
            if (config != null && config.containsKey("retentionDays"))
                fileRetentionDays = (int) config.get("retentionDays");
        } catch (Exception ignored) {}
    }

    private void cleanupOldFiles(File[] files, long cutoff) {
        if (files == null) return;
        for (File file : files) {
            try {
                Map<String, Object> data = persistence.loadYaml(file);
                Object tsObj = data != null ? data.get("timestamp") : null;
                long ts = tsObj instanceof Number ? ((Number) tsObj).longValue() : tsObj != null ? Long.parseLong(tsObj.toString()) : 0;
                if (ts > 0 && ts < cutoff) file.delete();
            } catch (Exception ignored) {}
        }
    }

    private void loadAllTracking(File[] files) {
        if (files == null) return;
        for (File file : files) {
            String uuidStr = file.getName().replace("-tracking.yml", "");
            try {
                UUID uuid = UUID.fromString(uuidStr);
                loadTrackingData(uuid);
            } catch (Exception ignored) {}
        }
    }

    private void setupDataFolder() {
        dataFolder = new File(getDataFolder(), "recoveries");
        if (!dataFolder.exists()) dataFolder.mkdirs();
        dumperOptions = getDumperOptions();
    }

    private void setupPersistenceAndCommands() {
        persistence = new RecoveryPersistence(dataFolder, dumperOptions);
        commandHandler = new RecoveryCommandHandler(trackedItems, this::scheduleTrackingSave);
    }

    private void registerEvents() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
    }

    private void handleConfigFile() {
        File configFile = new File(getDataFolder(), "config.yml");
        handleConfig(configFile);
    }

    private void cleanupOldRecoveryFiles() {
        long cutoff = System.currentTimeMillis() - (fileRetentionDays * 24L * 60 * 60 * 1000);
        cleanupOldFiles(dataFolder.listFiles((dir, name) -> name.endsWith(".yml")), cutoff);
    }

    private void loadAllPlayerTracking() {
        loadAllTracking(dataFolder.listFiles((dir, name) -> name.endsWith("-tracking.yml")));
    }

    // Update dropToPlayer map after assigning drop IDs
    public void updateDropToPlayerMap(UUID playerId, List<TrackedItem> items) {
        // Remove old drop IDs for this player
        dropToPlayer.entrySet().removeIf(e -> playerId.equals(e.getValue()));
        // Add new drop IDs
        for (TrackedItem ti : items) {
            UUID dropId = ti.getDropId();
            if (dropId != null) dropToPlayer.put(dropId, playerId);
        }
    }
}
