# RecoveryMod

A Minecraft Paper plugin that lets players in survival mode buy back their lost inventory after death.

## License
This project is licensed under the [Apache License 2.0](LICENSE).

## Setup
- Place the RecoveryMod jar in your server's `plugins` folder and restart the server.
- Edit `config.yml` (auto-created) to set how many days to keep recovery data (`retentionDays`, default: 30). **Restart the server after editing the config for changes to take effect.**

## Commands (OP only)
- `/recover <PlayerName>` — Restore items that have despawned.
- `/recover <PlayerName> list` — Show items lost (despawned) or still on the ground.

  - **Item Statuses:**
    - `[Despawned]` — The item has despawned and is eligible for recovery.
    - `[On Ground]` — The item is still present as an entity in the world and can be picked up normally.
    - `[Unknown]` — The item's state could not be determined (e.g., the entity is missing but no despawn event was detected).

  - **Response Structure:**
    - Each item is shown as:
      `[Status] ItemName xAmount`
    - Example: `[Despawned] DIAMOND_SWORD x1`

- `/recover <PlayerName> force` — Forcibly restore all tracked lost items, even if not despawned.

Picked-up items are not tracked or recoverable.
