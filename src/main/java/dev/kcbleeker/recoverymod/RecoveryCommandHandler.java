package dev.kcbleeker.recoverymod;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * Handles the /recover command logic and inventory listing.
 */
public class RecoveryCommandHandler {
    private final RecoveryPersistence persistence;
    private final File dataFolder;
    private final Map<UUID, Set<UUID>> trackedDrops;
    private final Map<UUID, Map<Integer, UUID>> slotToDropId;

    public RecoveryCommandHandler(RecoveryPersistence persistence, File dataFolder, Map<UUID, Set<UUID>> trackedDrops, Map<UUID, Map<Integer, UUID>> slotToDropId) {
        this.persistence = persistence;
        this.dataFolder = dataFolder;
        this.trackedDrops = trackedDrops;
        this.slotToDropId = slotToDropId;
    }

    public boolean handleRecoverCommand(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            return handleList(sender, args[0]);
        } else if (args.length == 1) {
            return handleRecover(sender, args[0]);
        } else {
            sender.sendMessage("Usage: /recover <PlayerName> [list]");
            return true;
        }
    }

    private boolean handleList(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage("Player not found or not online.");
            return true;
        }
        File file = new File(dataFolder, target.getUniqueId() + ".yml");
        Map<String, Object> data = persistence.loadYaml(file);
        if (data == null) {
            sender.sendMessage("No recovery data found for this player.");
            return true;
        }
        List<Map<String, Object>> serialized = (List<Map<String, Object>>) data.get("inventory");
        Map<Integer, UUID> slotMap = slotToDropId.getOrDefault(target.getUniqueId(), Collections.emptyMap());
        Set<UUID> tracked = trackedDrops.getOrDefault(target.getUniqueId(), Collections.emptySet());
        StringBuilder sb = new StringBuilder();
        sb.append("Lost inventory for ").append(target.getName()).append(":\n");
        for (int i = 0; i < serialized.size(); i++) {
            Map<String, Object> itemData = serialized.get(i);
            UUID dropId = slotMap.get(i);
            if (itemData != null) {
                String itemName = (String) itemData.getOrDefault("type", "?");
                int amount = (int) itemData.getOrDefault("amount", 1);
                // Only show items that are still tracked (on ground) or have despawned (dropId == null)
                if (dropId == null) {
                    sb.append("[Despawned] ");
                } else if (tracked.contains(dropId)) {
                    sb.append("[On Ground] ");
                } else {
                    continue; // picked up or otherwise not tracked
                }
                sb.append(itemName).append(" x").append(amount).append(" (slot ").append(i).append(")\n");
            }
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
        File file = new File(dataFolder, target.getUniqueId() + ".yml");
        Map<String, Object> data = persistence.loadYaml(file);
        if (data == null) {
            sender.sendMessage("No recovery data found for this player.");
            return true;
        }
        List<Map<String, Object>> serialized = (List<Map<String, Object>>) data.get("inventory");
        ItemStack[] items = new ItemStack[serialized.size()];
        Map<Integer, UUID> slotMap = slotToDropId.getOrDefault(target.getUniqueId(), Collections.emptyMap());
        Set<UUID> pickedUp = trackedDrops.getOrDefault(target.getUniqueId(), Collections.emptySet());
        for (int i = 0; i < serialized.size(); i++) {
            Map<String, Object> itemData = serialized.get(i);
            UUID dropId = slotMap.get(i);
            if (itemData != null && (dropId == null || pickedUp.contains(dropId))) {
                items[i] = null;
            } else if (itemData != null) {
                items[i] = ItemStack.deserialize(itemData);
            } else {
                items[i] = null;
            }
        }
        target.getInventory().setContents(items);
        sender.sendMessage("Inventory partially restored for " + target.getName() + ". Only despawned items were recovered.");
        return true;
    }
}
