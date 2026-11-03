# User-guide wording pass — plan and build brief (2026-11-03)

Scope: `landing/guide/*.html` (nine pages) and `landing/guide/guide-search.js`. Read `landing/README.md` first
(fingerprinting, the help-link anchor list near line 120). Voice benchmark: `landing/index.html` and
`landing/faq.html` — warm, straightforward, knowledgeable, measured. British English.

## Global rules
- Write for someone new to landscape photography without removing detail experienced photographers value.
  Explain technical terms on first use, then use them consistently. Prefer concrete descriptions over shorthand.
- Preserve exact app labels when quoting controls (e.g. "Worth it", "Best bet", "Tide at the light",
  "Ready questions", "Refresh drive times", "My area"). In explanatory prose prefer "sunrise or sunset" to
  "light"/"window", "location" to "spot", "drive time" to "drive".
- Introduce "Hot topics" as notable photographic conditions and events.
- Preserve filenames, section IDs (`id=` attributes — the app's help links use them), links, HTML structure,
  classes, scripts and styling. Include `<title>`, meta description, captions, `alt` text and figcaptions.
- Do not invent functionality or change entitlements. Where the page instructions conflict with the verified
  facts below, the FACTS win.
- Replace "one-word verdict" with "short verdict" everywhere ("Worth it" is two words).

## Verified facts (from the code, 2026-11-03) — use these
1. Hatching: two kinds. A faint hatch = places not yet rated for this sunrise/sunset. A darker veil with
   hatching = "Stood down": the weather (cloud, rain, poor visibility) ruled the area out; the cause is named
   on the map. Neither is drawn on aurora or astro nights. Plain ground = no PhotoCast locations there.
2. Fading: driven by the forecast's confidence (how far ahead, how much the ratings disagree, how many
   locations are rated), not by date as such. Lower confidence draws the colour more faintly/greyer on the
   At a Glance maps and fades the verdict; the lowest confidence is marked "provisional". Later days
   usually have lower confidence, so they usually look fainter.
3. Best bet: PhotoCast (Pro) picks ONE Best bet — a particular sunrise or sunset plus a region, usually with a
   named location and a short reason — across the whole few-day forecast, and at most one "Also good". At a
   Glance shows it on that sunrise/sunset's card naming the region; the Map marks that same sunrise/sunset in
   the window control and shows it in that region's panel. It is NOT one Best bet per sunrise/sunset —
   fix `at-a-glance.html` (~line 78) which says otherwise.
4. Coming up tide figure: the headline number is the tidal RANGE in metres (difference between high and low
   water, e.g. "4.1 m") on the biggest day of the period, at the named tide station. Not the height of high
   water. Rename the "Peak height" wording to "Biggest range" (or similar) with metres; day bars are ranges too.
5. Tides and stars: coastal star ratings already take the tide into account for everyone — a location's
   rating is lowered when the tide at sunrise or sunset doesn't suit it (and can rise by at most one star
   when it suits it well, especially a spring/king tide). The Map's Pro tide matching (dimming) only changes
   how the marker is drawn; it does not change the rating further. Fix `map.html` (~line 97) which says a
   wrong tide doesn't affect the rating, and the "rating unchanged" line in reading-the-forecast. Keep this
   consistent with the FAQ ("Ratings at coastal locations take the tide into account for everyone").
6. Reach rings: simple circles 25 and 50 miles from home, not calculated drive-time areas. Once drive times
   exist they're labelled roughly "45 min" and "1h 30" as a guide; otherwise "25 mi" and "50 mi". Anyone
   with a home set sees them (not Pro-only); they start switched on.
7. Map Scope: "My area" = regions within about three hours' drive of your home (regions without a measured
   drive time are included); "Everywhere" = all. When planning from somewhere else it reads "Around <place>".
   It moves the map and decides which locations the verdict considers; it doesn't hide locations. Lite
   readers with sub-regions also get "Your sub-regions"/"Everywhere", which only moves the map.
8. Aurora/space weather: checked every five minutes, day and night — during the day it watches tonight's
   outlook, after dark current conditions too. Fix `night-sky.html` (~line 36) "during the hours of darkness".
9. Local radius: straight-line distance from home, 10–50 miles (default 22). Used only by Ask PhotoCast for
   "near home" questions. Separate from the drive-time filter (Any / 45 min / 1h 30 / 2h 30 of road drive time).
10. Home location: no lock at all; you can change it any time. Changing it clears drive times until you press
    Refresh drive times (30-minute cooldown between refreshes). Do NOT add a one-week rule for home.
11. Sub-regions (Lite): up to 3. First 7 days after first choosing (counting that day): change freely. After
    that: adding while under the limit is always allowed and starts no lock; swapping or removing one is
    allowed once every 30 days. Pro covers every sub-region. Matches the FAQ.
12. Drive-time refresh: usually seconds but not guaranteed — say "When the calculation finishes, …".
13. Ask PhotoCast: the heading label is "Ready questions · free" (exact app label — keep "Ready questions").
    Ready answers don't use the daily allowance (footer: "Ready answer · no question used"). Pro allowance is
    30 typed questions per UK day; the counter reads "N of 30 left today" — fix the "3 of 3 left today"
    example (use e.g. "30 of 30 left today"). Context chips under "Asking about": a sunrise/sunset chip
    (removable with ✕), a view chip such as "Map · My area" (not removable), and "follow-up · N so far".
14. WX = weather-driven: only known from the forecast (mist, inversion, dust, storm surge, snow, aurora,
    noctilucent cloud …).
15. Meteor "~150/hr" = the shower's catalogue peak rate (ZHR) under ideal dark skies with the radiant
    overhead; actual counts are usually far lower.
16. Night filters: on aurora and astro nights the Subject filter and the dark-sky toggle are hidden, because
    those nights already use only dark-sky locations. Rating floor, drive time and scope stay.

## Owner's correction block (authoritative; overrides anything below)
- Home: users can change their home location at any time. The initial one-week change period applies only
  to sub-regions.
- Tides: coastal star ratings already account for tides on Lite and Pro. Map dimming is an additional visual
  indication of a tide mismatch; remove the claim that an unsuitable tide leaves the rating unchanged.
- Best bet: there is one Best bet across the whole forecast, not one for each sunrise or sunset. Correct At a
  Glance and align all other references.
- Coming up: change "Peak height" to "Peak tidal range". The figure is a range measured in metres.
- Reach rings: fixed circles at 25 and 50 miles from home. When drive times are available, their labels show
  approximate travel times. Do not describe them as calculated drive-time boundaries.
- My area: regions within approximately three hours' drive of home.
- Space weather: checks run every five minutes, day and night.
- Ask PhotoCast: retain the exact label "Ready questions". State the allowance of 30 typed questions per day
  and replace the incorrect "3 of 3" example.
- Hatching: explain both styles separately: faint hatching means not yet rated; darker hatching means weather
  conditions have ruled the area out.
- Forecast fading: colours fade with lower forecast confidence. Later dates are not automatically faded simply
  because they are further ahead. (This replaces the at-a-glance instruction "Forecast colours are shown more
  faintly for later days…" — word it as lower confidence, which is often but not always later days.)

Apply these corrections to explanatory text, examples, captions and image descriptions (`alt`, figcaptions,
meta descriptions) wherever relevant. Preserve exact interface labels. If any remaining editorial suggestion
conflicts with the implementation, do not resolve it silently: keep the existing wording for that sentence
and list the conflict in your report.

## Page-by-page instructions (apply, subject to the facts above)

### getting-started.html
- Keep the five-step structure and the early-alarm theme. "one-word verdict" → "short verdict".
- "Open the promising one" → "Explore a promising forecast" (heading text only; keep its id).
- "Conditions look promising somewhere you could reach" → "Conditions look promising at some locations",
  then explain separately that Pro readers can limit this to a chosen drive time.
- "topic pills" → on first use "small labels highlighting notable conditions or events".
- Replace "commonest coastal disappointment" sentence with: "The tide can change a coastal composition
  completely. Check whether the water level will suit the photograph you have in mind at sunrise or sunset."
- Briefly explain blue hour and golden hour (one short clause each).
- "A rating marked provisional is the one most likely to change" → "A provisional rating means confidence is
  lower, so it is especially worth checking again before you set off."

### reading-the-forecast.html
- Add the FAQ's one-to-five-star explanation (same meaning as faq.html — one/two, three, four, five).
- Define "window" if it remains: "A forecast window is a particular sunrise, sunset or night."
- Remove "so the other guides can stay short"; say what the page helps you understand.
- Explain provisional directly as lower confidence (fact 2).
- "They often disagree" → "The scores measure different qualities of light, so one can be high while the
  other is low." State Fiery Sky and Golden Hour are scores out of 100.
- Spring tide: "Spring tides have a larger difference between high and low water. They occur around new and
  full moons; the name does not refer to the season."
- "preferred water" → "preferred tide level". Fix the Dimmed line per fact 5.
- Hatching legend per fact 1 (distinguish the two). Fading per fact 2 ("on the map" is inaccurate — it's the
  At a Glance maps and verdicts).
- "Fixed": distinguish a predictable date from guaranteed conditions — e.g. a meteor shower's peak date is
  known but cloud may hide it; actual water levels can differ from predicted tides (wind, pressure).

### at-a-glance.html
- "masthead" → "top of the screen".
- "Every sunrise and sunset … only counts locations inside that drive" → "Each sunrise and sunset summary then
  considers only locations within your selected drive time."
- Explain "12 spots across 6 windows" as 12 locations across six sunrise and sunset forecasts, keeping the
  quoted UI text as quoted.
- Introduce the topic labels before describing outline styles and abbreviations. Explain WX (fact 14).
- "Later days look a little hazier" → "Forecast colours are shown more faintly for later days to indicate
  greater uncertainty." (adjusted to fact 2: lower confidence, usually later days).
- "how many spots have the tide they want" → "how many coastal locations have their preferred tide level at
  sunrise or sunset".
- Remove repeated Pro sentences where a heading badge plus one explanation already establish access.
- Best bet per fact 3.

### coming-up.html
- "keep the right mornings free" → "plan ahead for dates worth photographing".
- "Dated" → "Events with dates known in advance"; explain "Almanac" when it appears.
- "run" → "a period of several days" on first use.
- Meteor rates per fact 15.
- "the light is soft and raking from dawn to dusk" → "The sun stays low in the sky, which can produce long
  shadows and warm, directional light when conditions allow."
- "fresh snow … clean and untracked" → "Fresh overnight snow can simplify the landscape and cover existing
  tracks."
- Storm surge: water levels rising above the predicted astronomical tide (not synonymous with big waves).
- Expand NLC → "noctilucent clouds" + a short description (thin, very high clouds lit by the sun from below
  the horizon on summer nights, often electric blue).
- Explain "high water falls within an hour of sunrise or sunset" before using "tides at the light".
- Tide figure per fact 4.

### map.html
- "Choosing the light" → "Choosing a sunrise, sunset or night" (heading text; keep its id).
- "on this light" / "the light you're on" → "for the selected sunrise or sunset".
- First describe a "chip" as "a location marker showing its name and rating", then say "marker".
- Scope per fact 7. Reach rings per fact 6.
- "peek sheet" → "a summary panel at the bottom of the screen".
- "Locations that want the tide" → "Matching locations to the tide" (heading text; keep id).
- "gives those spots their water" → "matches those locations' preferred tide level".
- "high or low water is on the light" → "high or low water coincides with the selected sunrise or sunset".
- "the shading under it is the water" → "the shaded area shows the predicted tide height".
- Explain rising/falling directly (water level increasing/decreasing).
- Dimmed tip → "A dimmed location may still have promising light, but the predicted tide does not match its
  preferred tide level." and the rating claim per fact 5.
- Hatching per fact 1.

### night-sky.html
- Define astrophotography once (photographing the night sky); keep "Astro" when quoting the app label.
- Replace "After dark PhotoCast switches…" with wording describing the available night features.
- Kp: a measure of geomagnetic activity. Bz: the north–south component of the magnetic field carried by the
  solar wind; a sustained southward Bz can favour aurora.
- "should be visible" → "may be visible" throughout. Viewing line is an estimate, not a firm boundary.
- "A Strong alert breathes gently" → "A Strong alert gently pulses…".
- "The Milky Way casts detail" → "The Milky Way can show rich detail under clear, moonless conditions."
- Don't imply Bortle class alone decides aurora visibility.
- "the filters step aside" → fact 16. Polling per fact 8.

### ask-photocast.html
- Keep "Ready questions" (exact label). Replace "they appear instantly and are free" with "Ready answers are
  prepared in advance and do not count towards your daily question allowance."
- Fix the allowance example per fact 13.
- "So 'Is it worth going?' means this light, here" → "These labels show which forecast and map area your
  question refers to." Explain context labels (fact 13) before discussing removing them.
- "the map flies to it" → "the map moves to that location". Define "picks" as recommended locations and times.
- "the answer opens as a sheet" → "the answer opens in a panel".
- "doesn't guess" → "Ask PhotoCast uses the forecast information available in PhotoCast. It cannot provide
  reliable information about parking, cafés, opening times or crowds."
- Keep the example questions and follow-up examples.

### settings.html
- "Your home sets the sunrise and sunset times" → "Your home location determines the local sunrise and sunset
  times shown at the top of the app."
- Local radius per fact 9, distinguished from the drive-time filter.
- Sub-regions per fact 11: up to three on Lite; first 7 days free; adding / swapping / removing explained
  separately.
- Drive times per facts 10 and 12 ("When the calculation finishes…").
- Deletion → "Deleting your account is permanent. Enter your password to confirm; you will then be signed out."
  Check the delete-account flow in `frontend/src` really asks for a password and signs out; if not, word it
  to match and flag it.

### index.html
- Remove "Worth reading first" from Reading the forecast.
- "aurora and astro nights … darkest skies in reach" → "Aurora alerts, night-sky forecasts and locations with
  less light pollution."
- "dated events" → "events with dates known in advance". Explain Hot topics in its card/table description.
- Qualify the Lite star-rating row: ratings for up to three chosen sub-regions.
- "Tide at sunrise and sunset" in prose; keep "Tide at the light" where it's a feature label.

### guide-search.js
- "Loading…" → "Searching the guides…"; "Nothing found" → "No matching guide sections. Try ‘tide’,
  ‘drive time’ or ‘aurora’."
- Show "10+" only when more than ten matches exist: take 11, display 10, add "+" only if an 11th exists.
