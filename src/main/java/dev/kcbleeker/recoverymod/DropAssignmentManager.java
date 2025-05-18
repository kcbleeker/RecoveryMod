package dev.kcbleeker.recoverymod;

import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;
import dev.kcbleeker.recoverymod.RecoveryTracking.TrackedItem;

public class DropAssignmentManager {
    private final RecoveryMod plugin;
    private final Map<UUID, List<TrackedItem>> trackedItems;
    private final Runnable saveScheduler;

    public DropAssignmentManager(RecoveryMod plugin, Map<UUID, List<TrackedItem>> trackedItems, Runnable saveScheduler) {
        this.plugin = plugin;
        this.trackedItems = trackedItems;
        this.saveScheduler = saveScheduler;
    }

    public void scheduleDropIdAssignment(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                assignDropIds(player);
            }
        }.runTaskLater(plugin, 2L);
    }

    private void assignDropIds(Player player) {
        List<Item> drops = new ArrayList<>(player.getWorld().getEntitiesByClass(Item.class));
        Set<UUID> usedDropIds = new HashSet<>();
        List<TrackedItem> updated = new ArrayList<>();
        List<TrackedItem> current = trackedItems.get(player.getUniqueId());
        if (current == null) return;
        for (TrackedItem ti : current) {
            Map<String, Object> itemData = new HashMap<>(ti.getItemData());
            itemData.remove("type");
            ItemStack ref = ItemStack.deserialize(itemData);
            UUID dropId = null;
            for (Item drop : drops) {
                if (!usedDropIds.contains(drop.getUniqueId()) && drop.getLocation().distance(player.getLocation()) < 2.5 && drop.getPickupDelay() > 0) {
                    ItemStack dropStack = drop.getItemStack();
                    if (dropStack.getType() == ref.getType() && dropStack.getAmount() == ref.getAmount()) {
                        dropId = drop.getUniqueId();
                        usedDropIds.add(dropId);
                        break;
                    }
                }
            }
            updated.add(new TrackedItem(ti.getItemData(), dropId));
        }
        trackedItems.put(player.getUniqueId(), updated);
        // Update dropToPlayer map in RecoveryMod
        plugin.updateDropToPlayerMap(player.getUniqueId(), updated);
        saveScheduler.run();
    }
}
