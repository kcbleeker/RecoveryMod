package dev.kcbleeker.recoverymod;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles tracking of dropped item entities and slot mapping for a player's death.
 */
public class RecoveryTracking {
    private final Set<UUID> dropIds = new HashSet<>();
    private final Map<Integer, UUID> slotToDropId;

    public RecoveryTracking(Set<UUID> dropIds, Map<Integer, UUID> slotToDropId) {
        this.dropIds.addAll(dropIds);
        this.slotToDropId = slotToDropId;
    }

    public Set<UUID> getDropIds() {
        return dropIds;
    }

    public Map<Integer, UUID> getSlotToDropId() {
        return slotToDropId;
    }
}
