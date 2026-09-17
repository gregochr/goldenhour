### Fixed — the settings dialog's radius label reads as words in its own text, not "Local radiusHow"

#861 gave the radius slider its own name, "Local radius", but the label's text was left as it was.
Anything that reads that text rather than the slider's name still got
`Local radiusHow far counts as close to home.` That includes a screen reader's reading mode, copy
and paste, and `innerText`. The only gap between the two words was the hint's margin, which is not
text.

**The fix.** A real space now separates the label from its hint. It sits in a fixed 0.5rem
inline-block, which replaces the hint's 0.5rem margin, so the hint does not move. The box needs
`whitespace-pre`. Without it, a lone space at the start of the box collapses, and the text stays
glued in all three engines (measured). The slider's name and description are unchanged.

**Two simpler fixes were measured and rejected:**
- A bare space moves the hint 3.3 px (IBM Plex Sans at 14 px).
- A space plus a halved margin moves it −0.7 px.

**What was measured.** These readings are from the dialog's rendered DOM under the built stylesheet,
with the web fonts loaded. Main and this change were each served their own CSS, and every reading
was taken in Chromium, WebKit and Firefox, with and without a saved home, at 64 widths from 240 to
1440 px.

- **`innerText`:** was `Local radiusHow far counts as close to home.` in every engine, and is now
  `Local radius How far counts as close to home.`
- **Layout:** every text box and control box is identical to main at every width. A positive
  control confirmed the comparison can see a change: main's own layout differs at all 64 widths,
  and the bare-space variant differed from main at all 64 widths in each engine.
- **Chromium's native accessibility tree:** there is now a whitespace text node between the label's
  two text nodes, where before there was none. The field is still named `HOME LOCATION`, and the
  slider still `Local radius` with its hint as the description.
- **CSS:** the bundle gains one rule, `.whitespace-pre{white-space:pre}`, and loses none.
- **Not measured:** any screen reader's reading mode itself. The claim rests on the engines' text
  extraction and Chromium's accessibility tree. The running app was not seen either, because it sits
  behind sign-in.

**Tests.** One test pins that the label's own text reads as words. jsdom has no layout, so a second
pins the classes the browser measurement relies on:
- the space's box is `inline-block w-2 whitespace-pre`;
- it is not hidden from assistive tech;
- the hint no longer carries `ml-2`.

8 mutants were run, and all 8 were killed, each by the test that names its rule. Main's own
component fails both tests.
