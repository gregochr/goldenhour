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
