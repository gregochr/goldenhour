### Docs — the Escape order is two rungs, not the bundle README's three

Follow-on to #789, which corrected five comments that described a stack M5 refuses. Two more listed
the Escape order as `search → a stacked sheet → the popup`, and both were left there deliberately at
the time on the reading that a *precedence* list is not a claim about simultaneity. Overruled by the
owner, and the narrower reading is the better one: three rungs where only two can ever be occupied
invites exactly the misreading the five fixed comments had already made.

- `WindowFirstShell`'s `stackedOverPopup` docblock said it twice — once as "search, then a sheet
  stacked over the popup, then the popup itself (plan-matrix §6 M2.5, and the bundle README's own
  ordering)", once as a closing restatement.
- `WindowSheetDialog`'s `closeOnEscape` said "search → a stacked sheet → this".

Both now read as "the layer above, then the popup", with the two upper rungs named as
**alternatives**: since M5 every route into search is refused while anything is stacked over the
popup, so the rung above is search *or* a sheet and never both (plan-matrix §4 A22). The historical
narrative in the shell's docblock is untouched — search's rung really was dormant through M2 and
went live at M3, and that is still why the ordering exists at all.

⚠️ The bundle README's three-rung order is now contradicted in three places rather than one
(`LocationFourDaySheet` got the same treatment in #789). That is deliberate: the README is a design
input, the guards are the shipped behaviour, and plan-matrix §4 A22 is where M5 recorded the
divergence.

Swept the rest of the frontend for the pattern; there were exactly two copies and no others.
`WindowSpotSheet` and `WindowPickDialog` — the two sibling stacked layers — already phrase it
generically ("declines the key while something sits over it"), which stays correct at any depth and
needed no change.

Comment-only; no behaviour changed and no prop moved.
