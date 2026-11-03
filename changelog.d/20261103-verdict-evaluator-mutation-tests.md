### Changed — Boundary tests for the briefing verdict evaluator

Added `BriefingVerdictEvaluatorBoundaryTest`, which pins every threshold in `BriefingVerdictEvaluator` (low cloud, precipitation, visibility, humidity, mid cloud, horizon cloud, cloud trend, clear-all-layers) at, just below and just above its constant, plus every flag, tide label, region summary variant and roll-up edge. PIT on the class went from 98 killed / 36 survived / 6 uncovered to 135 killed / 1 survived / 4 uncovered. Test-only change.
