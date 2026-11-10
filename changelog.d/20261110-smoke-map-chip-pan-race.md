### Fixed — E2E smoke: the Map chip test no longer races the callout's pan

Test-only. The Map smoke test closed the first chip's callout and immediately sampled the visible chips, but opening a callout pans the map for about 300 ms and the labels re-place only when the pan ends, so a chip near the bottom edge could be sampled and then removed by the placer before it was clicked. The test now waits for the pan to finish and re-reads the chips on each attempt when it picks the second one. No product code changed.
