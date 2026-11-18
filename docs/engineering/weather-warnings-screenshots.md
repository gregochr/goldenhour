# Weather warnings guide: screenshot collection list

The guide pages carry visible placeholders (`<figure class="shot" id="shot-warn-..."><div class="ph">`) where a
screenshot is still to be taken. Replace each with a real capture; the figure id must stay, because the overview
page links to it. `frontend/src/test/guideShotPlaceholders.test.js` pins the ids and fails if a figure is lost.

## Environment

- Warnings show only with `photocast.warnings.enabled=true` AND real or fixture-served warnings. Simulated rows
  never reach user surfaces, so the local backend's simulate toggle cannot produce a capture.
- `backend/src/test/resources/warnings/real/` holds recorded feed fixtures: use them only as a reference for
  realistic wording and levels. Never use Met Office artwork, logos or warning icons in a capture or elsewhere.
- Capture as a Pro or Admin reader for the Ask shot (Ask needs its flag on); the others are visible to Lite too.
- Do not stage a capture that suggests an all-clear. Never crop out the Met Office credit or the stale line.

## Collection list

| Id | Page | Capture | Surface and viewport |
|---|---|---|---|
| `shot-warn-lanes` | at-a-glance.html (#warnings) | A warning lane above the event lanes, and a card below with its marker (for example "Amber snow +1") | Desktop; 375px phone if the lane wraps differently |
| `shot-warn-popup` | weather-warnings.html (#matrix) | Window popup Warning row: headline, what to expect, dates, the likelihood x impact matrix with its tick | Desktop popup; 375px phone sheet optional |
| `shot-warn-stale` | weather-warnings.html (#fresh) | The line "Warnings last checked HH:MM — check the Met Office" under the warning area | Desktop; needs the feed unreachable for about ten minutes (stop the poller or block the feed host) |
| `shot-warn-ask` | weather-warnings.html (#where) | An Ask answer that carries a warning card with dates and the Met Office credit | Desktop with Ask open beside the Map; 375px phone if it reads better |
| `shot-warn-map-chips` | map.html (#warnings) | The chip row under the topic chips for a sunrise or sunset window | Desktop Map; 375px phone as an alternate |
| `shot-warn-callout` | map.html (#warnings) | A location callout carrying a warning chip | Desktop Map |
| `shot-warn-coming-up` | coming-up.html (#warnings) | A "Met Office warning" entry showing "Raised from ..." and "Updated" | Desktop Coming up |

`reading-the-forecast.html` carries no placeholder: its warnings section is a short statement that links to the
guide. The home page (`landing/index.html`, feature "/ 08") is not changed. Each shot lives on one page; the
overview, `weather-warnings.html`, links to the others instead of repeating them.

## Capture rules

- Width: 1600 px wide `.webp`, as most guide shots are. Height follows the content.
- Crop to the relevant surface: the lane and cards, the popup, the chip row. No browser chrome, no personal
  data (home address, email). Keep the Met Office credit line in frame where the surface shows it.
- File name: `landing/guide/shots/<name>.webp`, where the name is the id without the `shot-` prefix, as the
  other shots do (`shot-warn-lanes` becomes `shots/warn-lanes.webp`).
- Alt text: describe what is shown, starting with the surface, and give the level as a word and the weather
  type ("Amber warning of snow and wind"). Mention a colour only alongside the level word. Never describe an
  absent warning as safe or clear.

## Replacing a placeholder

Swap the `.ph` block for the image and keep the figure, its id and its caption:

```html
<figure class="shot wide" id="shot-warn-lanes"><img src="shots/warn-lanes.webp" width="1600" height="..." alt="..."><figcaption>...</figcaption></figure>
```

Then move the id in `guideShotPlaceholders.test.js` from the placeholder list to a real-image check.
