### Accessibility — map chips, topic chips, region rail and topic-strip days say their visible text

axe's `label-content-name-mismatch` rule (WCAG 2.5.3, Label in Name) glued the visible words of four controls together
("Simonside Ridge4", "tidemid") because their sibling spans had no text separator. Each now has a real space between
the items (invisible: they sit in flex containers; before/after screenshots are pixel-identical), and the accessible
names are unchanged: the Map's place chips, the Map's hot-topic chips, and the window sheet's region rail cells. The
topic strip's current-day button now leads its name with the visible day label ("SUN, On this window · Spring tide").
The Plan heat card and the Coming up day chips are still to do. New unit tests run axe's own check in jsdom.
