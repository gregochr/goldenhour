### Accessibility — the Plan heat card's control is a stretched button holding only its spoken name

axe's `label-content-name-mismatch` rule (WCAG 2.5.3, Label in Name) failed every Plan heat card in the plan, window-sheet
and location-sheet scans, at both widths: the card was a `<button>` whose visible spans were all `aria-hidden`, which axe
counts as visible text and wants inside the name. The card is now a `div` (same classes, `data-*` marks and placement)
holding one transparent button, stretched over it, whose only content is the same spoken sentence, so a screen-reader
user hears exactly what they did and the rule has no visible text to compare. The pointer still lands on the card (the
tooltips, the pick legend's overhang and the cursor are unchanged), the keyboard and assistive tech use the button, a
click focuses it so focus returns to it when the window sheet closes, and the two app-side focus moves look for it.
Before and after screenshots of the matrix, at desktop and phone, resting, hovered, focused, picked and in forced colours,
are pixel-identical apart from three anti-aliased pixels (one ring corner, one neighbouring card's corner). New tests run axe's own check on a rated card,
both picks, an unscored card and an Ask-highlighted card, and check the visible time, verdict, sun and pick words are in
the name.
