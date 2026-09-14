### Docs — the accessible-name notes say what measured them

`#825`'s reviewer stalled and was only resumed after the merge. Its late review, and a second
review of this change, found the accessible-name notes still overstating their evidence, and
`#825`'s own entry miscounting. Nothing about *which* spaces matter was wrong: every separator the
harness found changing a name carries a note, and every note's conclusion holds. Comments only:
the production build is byte-identical.

- **Eighteen comments credited a reading to Chromium, WebKit and Firefox, "every engine",
  "browsers" or "a screen reader".** Those readings came from Playwright's accessible-name
  algorithm run over each engine's layout. The only native reading is Chromium's own accessibility
  tree: the Coming up handoff and entry card on 2026-09-05, and all 4,167 separators on 2026-09-14.
  Each comment now says which instrument read what, or states the rule and points to the class doc
  that does.
- **Ten comments stated JSX's whitespace rule too broadly.** Seven said, in one wording or another,
  that JSX drops whitespace-only text between tags. `#825`'s three reflow warnings said "JSX drops
  whitespace that contains a line break". In fact JSX drops whitespace-only text that contains a
  line break. A space on the same line as its neighbours is kept, and a line break inside text
  collapses to one space.
- **Three notes said a name *reads* the glued pair.** In fact the name only contains it, among the
  rest of the row or card: `PromptTestView`, `ComingUpTideSparkline` and `WindowComingUpEntry`.
- **`MapRegionPanel`'s test blamed its `5stars` on the polyfill gluing any adjacent
  contributions.** The polyfill also trims the leading space inside the `sr-only` " stars" span,
  which browsers keep.
- **Two notes claimed less than was measured.** All three of `RegisterPage`'s terms separators
  were measured on 2026-09-14, as was the Coming up conditions' `peak` / date separator.
- **The canonical class doc now says what its runs bear on.** They cover the rule's first two
  items, except the browse-mode clause, which no run read. They also cover the fifth, through the
  space inside `SlotLocationName`'s icon span. The third and fourth items rest on earlier targeted
  probes.
- **Three corrections to `#825`'s entry**, which this directory's rules do not allow rewriting:
  - The 119 name-changing separators sit at `#825`'s four sites or at *three* places `#819` had
    annotated, not at "four" places with "both" of `RegisterPage`'s separators. The three places
    are all *three* of `RegisterPage`'s terms separators (which the harness grouped as two shapes),
    `WindowComingUpConditions`' name and cadence, and the Coming up tide chart.
  - The other 906 change no accessible name, but they do change the layout. Where one sits in
    plain inline text, the rule says browse mode reads the two words glued as well. That was never
    measured, so "change only what is drawn" claimed more than was known.
  - JSX deletes whitespace-only text that contains a line break, not "any whitespace" that does.
