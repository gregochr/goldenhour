# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast.zip` / `design_handoff_lunar_eclipse/`, 2026-09-23.
**Do not edit these files.** Where they and the codebase disagree,
`docs/engineering/lunar-eclipse-plan.md` §1 (what already exists), §4 (deliberate disagreements)
and §6 (owner decisions) win.

| file | what it is |
|---|---|
| `README.md` | **The specification.** Colours, type, copy and the data model. Its §7 data model assumes two things this codebase does not have (a `clearToDeg` horizon score and a promoted strip) — the plan's §1 table says what replaces each. |
| `Lunar Eclipse.html` | The design of record: all four screens, the solar-vs-lunar comparison (§05), the catalogue (§06) and the four open questions. Its doc prose (`.hd`, `.life`, `.bar`, `.cap`, `.cmp`, `.spec`, `.foot`) is annotation, not product. |
| `Coming Up.html` | The Coming up tab with the lunar row on an older illustrative date (27 Sept), and the README §6 plain-language copy change. The notes beneath the frame still explain the model in bits — that is for engineering, not UI. |
| `Plan Tide Summary.html` | The tide-plan prototype with the lunar chip injected on the Friday sunrise card by an inline `<script>` at the foot of the file (`PLAN.TOPICS.lecl`, `PLAN.WTOPICS.w6`). Differs from `docs/design/tide-plan/Plan Tide Summary.html` only by that injection. |
| `reference/` | The solar eclipse handoff this extends — its README and `Eclipse Topic v2 (solar).html`. The solar feature shipped in #485/#489; the README's promoted strip has since been retired (plan §1). |
| `screenshots/01–08` | Desktop (01–04) and phone (05–08) captures of the four screens. |

**Three files from the bundle are not copied here, on purpose.** `heat-field.js` and `plan-data.js`
are byte-identical to `docs/design/map-tab-v2/`'s, and `plan-tide-v6.js` is byte-identical to
`docs/design/tide-plan/plan-tide-v6.js` (all verified with `cmp` at vendoring time). To run the
prototype, copy the three in from there.

⚠️ The worked example, Friday 28 August 2026, is **already past** at vendoring time. The first
lunar eclipse the shipped feature can raise is the next umbral one visible from the UK (the plan's
§2.2 lists the candidates to verify), so browser verification runs on the simulation template and
the 2026-08-28 fixture, not on a live event.
