### CI — Stryker mutation testing gets a per-shard floor

The weekly Stryker run now fails a shard that scores under its own floor, set from the first full run after the survivor sweeps (94 to 97 percent per shard, 96.28 percent overall). The floor comes from the workflow matrix through `STRYKER_BREAK`; local runs have no floor. The landing pages now say the app's client logic is mutation-tested.
