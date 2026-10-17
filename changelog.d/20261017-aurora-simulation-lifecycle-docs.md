### Docs — CLAUDE.md records how an admin aurora simulation ends

The Aurora API notes listed `POST /api/aurora/admin/simulate` and `POST /api/aurora/admin/simulate/clear`
without saying what either does to the state machine. The guess a reader would make — that Clear is
`POST /reset` under another name — was true until the simulation-lifecycle fix and is not now, so the
notes now say what the code does, read from `AuroraStateCache` and `AuroraOrchestrator` rather than
from their javadoc:

- A simulation ends on the next real reading that reaches `evaluate()` — a quiet one clears it, an
  alert-worthy one replaces it with a new real alert — or on `simulate/clear`, whichever is first.
- A night poll that would CLEAR holds instead while a block's NOAA reading is still due, and
  `wouldClear` counts a simulated alert, so a simulation can outlive the next poll.
- `simulate/clear` calls `endSimulation()`, which acts only while a simulation stands; with none
  running it changes nothing and still answers 200, the status saying nothing was cleared. `POST /reset`
  is the call that wipes the machine, a real alert included.

No code changed.
