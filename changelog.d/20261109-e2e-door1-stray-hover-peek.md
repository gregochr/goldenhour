### Fixed — E2E door1-obstacles no longer fails on a stray hover peek

The required `E2E — Playwright smoke` check went red on `door1-obstacles.spec.js` ("the door button overlaps a chip"). The door button was not the cause: Playwright's card click left the pointer at the card's centre, and when the opened sheet put a spot card under it, that card's hover peek (`wf-peek`) covered a field chip. The spec now parks the pointer off the sheet before sampling, and its failure message names the covering element. The overlap assertion, sampling and box check are unchanged.
