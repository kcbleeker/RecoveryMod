package dev.kcbleeker.recoverymod;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import dev.kcbleeker.recoverymod.RecoveryTracking.TrackedItem;

/**
 * Handles the /recover command logic and inventory listing.
 */
public class RecoveryCommandHandler {
    private final Map<UUID, List<TrackedItem>> trackedItems;
    private final java.util.function.Consumer<UUID> scheduleTrackingSave;

    public RecoveryCommandHandler(Map<UUID, List<TrackedItem>> trackedItems, java.util.function.Consumer<UUID> scheduleTrackingSave) {
        this.trackedItems = trackedItems;
        this.scheduleTrackingSave = scheduleTrackingSave;
    }

    public boolean handleRecoverCommand(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            return handleList(sender, args[0]);
        } else if (args.length == 2 && args[1].equalsIgnoreCase("force")) {
            return handleForceRecover(sender, args[0]);
        } else if (args.length == 1) {
            return handleRecover(sender, args[0]);
        } else {
            sender.sendMessage("Usage: /recover <PlayerName> [list|force]");
            return true;
        }
    }

    private boolean handleList(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage("Player not found or not online.");
            return true;
        }
        List<TrackedItem> items = trackedItems.get(target.getUniqueId());
        if (items == null || items.isEmpty()) {
            sender.sendMessage("No recovery data found for this player.");
            return true;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Lost inventory for ").append(target.getName()).append(":\n");
        int idx = 0;
        for (TrackedItem ti : items) {
            Map<String, Object> itemData = ti.getItemData();
            UUID dropId = ti.getDropId();
            String itemName = null;
            if (itemData.containsKey("type")) {
                itemName = (String) itemData.get("type");
            } else if (itemData.containsKey("id")) {
                String id = (String) itemData.get("id");
                if (id.startsWith("minecraft:")) {
                    itemName = id.substring("minecraft:".length()).toUpperCase();
                } else {
                    itemName = id.toUpperCase();
                }
            } else {
                itemName = "Unknown Item";
            }
            int amount = (int) itemData.getOrDefault("amount", itemData.getOrDefault("count", 1));
            Object despawnedVal = itemData.get("_despawned");
            String despawnedType = despawnedVal == null ? "null" : despawnedVal.getClass().getName();
            Bukkit.getLogger().info("[RecoveryMod] [List] Item #" + (idx+1) + " _despawned value: " + despawnedVal + " (type: " + despawnedType + ")");
            String state;
            if (Boolean.TRUE.equals(despawnedVal) || "true".equals(despawnedVal)) {
                state = "[Despawned]";
            } else if (dropId != null) {
                // Check if the entity still exists in any world
                boolean found = false;
                for (org.bukkit.World world : Bukkit.getWorlds()) {
                    if (world.getEntity(dropId) != null) {
                        found = true;
                        break;
                    }
                }
                state = found ? "[On Ground]" : "[Unknown]";
            } else {
                state = "[Unknown]";
            }
            sb.append(state).append(" ").append(itemName).append(" x").append(amount).append(" (#").append(idx + 1).append(")\n");
            idx++;
        }
        sender.sendMessage(sb.toString());
        return true;
    }

    private boolean handleRecover(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage("Player not found or not online.");
            return true;
        }
        List<TrackedItem> items = trackedItems.get(target.getUniqueId());
        if (items == null || items.isEmpty()) {
            sender.sendMessage("No recovery data found for this player.");
            return true;
        }
        List<ItemStack> toRestore = new ArrayList<>();
        boolean foundDespawned = false;
        for (TrackedItem ti : new ArrayList<>(items)) {
            Map<String, Object> itemData = ti.getItemData();
            if (Boolean.TRUE.equals(itemData.get("_despawned"))) {
                itemData = new HashMap<>(itemData);
                itemData.remove("type");
                itemData.remove("_despawned");
                toRestore.add(ItemStack.deserialize(itemData));
                foundDespawned = true;
            }
        }
        if (!foundDespawned) {
            sender.sendMessage("No despawned items to recover for this player.");
            return true;
        }
        for (ItemStack stack : toRestore) {
            target.getInventory().addItem(stack);
        }
        sender.sendMessage("Inventory partially restored for " + target.getName() + ". Only despawned items were recovered.");
        return true;
    }

    private boolean handleForceRecover(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage("Player not found or not online.");
            return true;
        }
        List<TrackedItem> items = trackedItems.get(target.getUniqueId());
        if (items == null || items.isEmpty()) {
            sender.sendMessage("No recovery data found for this player.");
            return true;
        }
        List<ItemStack> toRestore = new ArrayList<>();
        // Mark all as despawned before restoring
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> itemData = new HashMap<>(items.get(i).getItemData());
            itemData.put("_despawned", true);
            itemData.remove("type");
            itemData.remove("_despawned"); // Remove before deserialization
            toRestore.add(ItemStack.deserialize(itemData));
            items.set(i, new TrackedItem(itemData, null));
        }
        for (ItemStack stack : toRestore) {
            target.getInventory().addItem(stack);
        }
        // Remove all tracked items for this player
        trackedItems.remove(target.getUniqueId());
        scheduleTrackingSave.accept(target.getUniqueId());
        sender.sendMessage("All lost items forcibly restored for " + target.getName() + ".");
        return true;
    }
}
