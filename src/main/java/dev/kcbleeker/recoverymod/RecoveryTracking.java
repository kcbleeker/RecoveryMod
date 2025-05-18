package dev.kcbleeker.recoverymod;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a tracked lost item with its serialized data and drop entity UUID.
 */
public class RecoveryTracking {
    public static class TrackedItem {
        private final Map<String, Object> itemData;
        private final UUID dropId; // null if despawned

        public TrackedItem(Map<String, Object> itemData, UUID dropId) {
            this.itemData = itemData;
            this.dropId = dropId;
        }

        public Map<String, Object> getItemData() {
            return itemData;
        }

        public UUID getDropId() {
            return dropId;
        }
    }

    private final List<TrackedItem> items;

    public RecoveryTracking(List<TrackedItem> items) {
        this.items = items;
    }

    public List<TrackedItem> getItems() {
        return items;
    }
}
