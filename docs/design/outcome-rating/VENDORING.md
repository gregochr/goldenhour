# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast Outcome Recroding.zip` / `design_handoff_outcome_rating/`,
2026-10-15. **Do not edit these files.** Where they and the codebase disagree,
`docs/engineering/outcome-rating-plan.md` §1 (corrections), §4 (deliberate disagreements) and §5
(decisions) win.

| file | what it is |
|---|---|
| `README.md` | **The specification.** Where the rating block appears, the window control's backward step, every state and its exact copy, the data model, the client state, the tokens. Matched exactly except where the plan's §1/§4/§5 record why this codebase already answers a question differently (the callout's own width and class names, "Yesterday" not yet existing, the forecast run's identity, the heat field on a past window). |
| `Outcome Prototype.html` | **The design**, as a clickable prototype (its demo bar switches view, role, "now" and forces a failed save). **Design reference, not production code** — the README says so itself: do not port its imperative DOM. The rating block is `rateBlock()` (`:166–190`), the window menu's EARLIER group is inside `render()` (`:209–221`), the older-day fetch is `fetchOlder()` (`:191–196`), the pin badge is the `.mine` span (`:202`). Its map, pins and coast are placeholders. |
| `Outcome Flow v2 (static states, superseded layout).html` | Earlier static boards on a bottom-sheet layout and a date strip. **The layout is superseded** by the prototype. Kept for its copy table (§03, every string) and its decisions list (§04–§05), which the prototype inherits. |
| `screenshots/01–15` | The fifteen prototype captures the README's §7 names, phone and desktop. |

**Nothing from the bundle is omitted.** It carries no shared `heat-field.js` / `plan-data.js` copies,
unlike the Plan-tab bundles.

⚠️ Two of the prototype's figures are not this codebase's: the phone callout is **266px**, not 296
(`MapCallout.CALLOUT_WIDTH_MOBILE`), and there is no `#cal` / `.cv2` — the callout is
`.wf-callout` and its verdict strip `.wf-callout-verdict` (plan §1 #3). The README's "set `/forecast`
to 2 past days" open item is already true (`ForecastHorizon.SERVE_PAST_DAYS = 2`, plan §1 #1).

**Final count, O6 sweep (2026-10-16):** the port plan's §4 "Disagreements with the spec, on purpose"
closed at **32 entries** once every phase (O0–O6) had shipped — the original 22 written before any
code existed (#1–#22, several raised by Codex across the plan's own review rounds), plus #23 (O4b: a
pin's badge is unreserved, only chips reserve its overhang), #24 (O4b: an Ask pick never wears a
badge), #25 (O5a: the admin list's time column is 56px), #26 (O1: a re-rate keeps the provenance
recorded the first time), #27 (O1: `findFirstByTriggerTimeOrderByIdAsc`), #28 (O3b: the older-day
merge is a derived memo), #29 (O3b: the menu scrolls itself to the active row), #30 (O4b: counts and
badges read `ratingsKnownFor`, unknown is not zero), #31 (O2: LITE's star on a passed slot is the
row's own) and #32 (O2: a passed slot with nothing available is no row, for a few days after V178).
⚠️ The prototype's map, pins and coast are placeholders, so the widths the plan relies on (266 / 238 /
43.59 / 44.39px) were measured on the real components, not read off the prototype (plan §7).
