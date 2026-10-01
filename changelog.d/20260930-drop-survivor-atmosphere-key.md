### Removed — the legacy `photocast.survivor-atmosphere.write` config key

`SlotAtmosphereWriter` no longer reads the old `photocast.survivor-atmosphere.write` key as a
legacy alias for the renamed `photocast.slot-atmosphere.write` (V159, 2026-09-30). Production's
config carries neither key, so the default (`true`) applies either way; the alias existed only to
protect a deployment that had not yet picked up the new key name, and is dropped now that none
does. `photocast.slot-atmosphere.write` is read alone from this change on.
