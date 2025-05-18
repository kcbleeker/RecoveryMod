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
- `/recover <PlayerName> force` — Forcibly restore all tracked lost items, even if not despawned.

Picked-up items are not tracked or recoverable.

## Important Note: Paper ItemDespawnEvent Bug
RecoveryMod relies on the `ItemDespawnEvent` to detect when dropped items are truly lost (despawned). **On default Paper configurations, this event will fire as expected and RecoveryMod will work correctly.**

However, if you have set a custom `despawn-time` for items in your `paper-world.yml`, there is a known bug in Paper where `ItemDespawnEvent` does not fire for those items. This can prevent some lost items from being marked as recoverable.

Example `paper-world.yml` configuration that triggers the bug:

```yaml
# paper-world.yml
# ...existing config...
despawn-time:
  item: 2000
```

A fix is pending in this Paper PR: https://github.com/PaperMC/Paper/pull/12561

If you experience issues with items not being recoverable after despawn, check your `paper-world.yml` configuration and follow the progress of the above PR. Once merged and released, updating your server will resolve this issue.
