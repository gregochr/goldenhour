### Docs — `withEvaluationGate`'s javadoc says what is true today

#971 put the method's javadoc back on it, in its 2026-09-17 wording. That wording predates the tide
gate lift: it reads as though slots are still withheld. The block now says that `BriefingSlotBuilder`
is the one production caller and never fires, since `HARD_CONSTRAINT_REASONS` has been empty since
2026-09-18, that the method stays for the next hard physical constraint, that tests use it to
fabricate a gated slot, and that `eclipse` is carried through by the canonical constructor. No
behaviour change.
