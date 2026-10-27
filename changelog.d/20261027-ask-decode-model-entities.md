### Fixed — Ask no longer shows "&amp;" in a summary

The model occasionally wrote an HTML entity in its answer ("Northumberland &amp; Tyneside"), and the
client prints text verbatim. `AskAnswerParser` now decodes the basic named entities and numeric
character references in the model's free text only (`summary`, `missing`, each pick's and event's
`why`), in a single pass; window ids, event types and dates are left exactly as written.
