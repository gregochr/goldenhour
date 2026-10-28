### Changed — hot topics read their inputs once per serve

Building the hot topics for a briefing serve used to run the slot-signal read (four queries) once in each of seven
strategies and load the location rosters about a dozen times across the strategies and the event enricher. The
aggregator now builds one `HotTopicInputs` per call and every strategy, the enricher and the travel-day filter read
from it, so the slot signals are read once and each roster at most once. The topics themselves are unchanged
(`HotTopicInputsEquivalenceTest` runs the real strategies both ways over one fixture and compares the output).
