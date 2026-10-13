### Changed — prompt-test and model-test replays skip fields a different build wrote

The Prompt Test and Model Test replays read stored atmospheric data JSON with a mapper that now skips a property it does not know, and logs each skipped property at WARN each time it is met, instead of failing the whole replay. Rows written by a newer build (for example with the far-point cloud fields) can then still be replayed after a rollback. A renamed field shows up in the log rather than quietly replaying a different prompt.
