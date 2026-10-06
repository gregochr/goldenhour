### Fixed — Ask PhotoCast Ready answers carry only what their question is about

A Ready answer is the model's, held to what the tools returned, but nothing checked that an event or a pick was
relevant to the question asked: a "snow on the tops" answer could carry an aurora, because the validator only proves
an event came from a tool. Each `ReadyQuestion` now says which event types it keeps (`RARE_EVENTS` any; `SNOW_TOPS`
only `SNOW_TOPS`, `SNOW_FRESH` and `SNOW_MIST`; every pick question none) and which picks (the question's own
windows, in scope, and for `COASTAL_HIGH` a coastal slot at high water; an events question none), from one predicate
used both when an answer is stored and when it is served. At store time irrelevant events are removed, an answer
with nothing relevant left, or one that would lose an event carrying a safety warning, is not stored; at serve time a
row written under an older rule is withheld rather than served. Nothing reads these answers yet.
