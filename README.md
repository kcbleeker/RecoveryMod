# RecoveryMod

A Minecraft Paper plugin that lets players in survival mode buy back their lost inventory after death.

## Setup
- Place the RecoveryMod jar in your server's `plugins` folder and restart the server.
- Edit `config.yml` (auto-created) to set how many days to keep recovery data (`retentionDays`, default: 30). **Restart the server after editing the config for changes to take effect.**

## Commands (OP only)
- `/recover <PlayerName>` — Restore items that have despawned.
- `/recover <PlayerName> list` — Show items lost (despawned) or still on the ground.

Picked-up items are not tracked or recoverable.
