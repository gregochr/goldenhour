### Fixed — the Plan popup's region rail is two columns on every phone again

The design's phone rail is two up, with the "All regions" cell on its own row. It rendered three or
four columns on the wider phones. The phone rule, `.wf-rrail { grid-template-columns: 1fr 1fr }`,
was 0,1,0 and never once won: the rail is only ever mounted inside the popup, where the unscoped
`.wf-wsh .wf-rrail { repeat(auto-fit, minmax(128px, 1fr)) }` is 0,2,0 — so on a phone the desktop
auto-fit decided the columns. The prototype had the matching rule all along
(`.wrap.mob .wside .rrail`); the port kept only its unscoped twin.

The fix writes `.wf-wsh .wf-rrail { 1fr 1fr }` into the popup's own phone block, after the desktop
rule — equal specificity, later source order — and retires the dead line with a pointer, so it is
not re-added at the specificity that cannot win.

**Measured in real headless Chromium, inside the popup's actual chain** (the modal root the phone
rule targets by `data-testid="window-sheet"`, `.wf-wsh-b`'s 12px padding, `.wf-wsh-side`), with the
old rule re-imposed for the "before" column rather than computed:

| phone width | 375 | 390 | 414 | 430 | 500 | 600 | 639 |
|---|---|---|---|---|---|---|---|
| rail width | 351 | 366 | 390 | 406 | 476 | 576 | 615 |
| before | 2 | 2 | 2 | **3** | **3** | **4** | **4** |
| after | 2 | 2 | 2 | 2 | 2 | 2 | 2 |

iPad (640, 834) and desktop (1024) are identical before and after, and the rail's margins stay 0 at
every width.

⚠️ **This corrects the record in #821's entry**, which said the rail "renders 3 columns where the
spec says 2" and — in its PR and commit — "3 columns at 390px". That was measured in a probe box
forced to 398px wide. On a real 375–414px phone the broken rule happens to give two columns,
because the rail is too narrow for a third 128px track; the defect only shows from 430px (an iPhone
Pro Max) upward. The fix is the same; the reach was narrower than stated.

**Deliberately NOT changed:** the phone block's two MARGIN rules beside it are dead too, and on
purpose. `.wf-wsh .wf-rrail { margin: 0 }` and `.wf-wsh .wf-rlab { margin: 0 0 5px }` override them
inside the dialog, where the body already owns the inset — reviving them would indent the rail
twice. Only the columns were an accident, so only the columns moved.

An adversarial review (read-only, one lens) refuted all six charges it was set: the rail has no
renderer outside the popup, so the retired line was never live anywhere; the phone and iPad ranges
meet without overlapping; the All cell still spans; no margin was revived; the arithmetic
reproduces from the rail's 6px gap; and no test pins the old line. It also re-derived the table
above independently.

**Found in passing, not fixed:** the desktop rail has the same README-versus-prototype split the
iPad block settled. The README and the code say 128px, but the prototype's rule for the rail inside
the popup's side column — the only place it renders — is `.wside .rrail` at **112px**, and that is
the one on screen. So the prototype runs 112px desktop, 130px iPad, two up on phone; the code now
matches on iPad and phone only.
