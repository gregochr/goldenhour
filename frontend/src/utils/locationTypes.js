/**
 * Single source of truth for `LocationType` presentation and classification.
 *
 * <p>This lived in five copies: `MapView.LOCATION_TYPE_LABELS`,
 * `LocationTypeBadges.LOCATION_TYPE_META` (that component is since deleted as dead code, v1
 * retirement D4), `MarkerPopupContent.POPUP_LOC_TYPE_META`, `briefingDisplay.LOCATION_TYPE_ICONS`
 * (that re-export is likewise since deleted — `briefingDisplay` still re-exports `locationTypeIcons`,
 * its live consumer) and `LocationManagementView.LOCATION_TYPES`, plus three open-coded type
 * predicates. Adding WOODLAND cost five hand-edits that nothing forced you to make, and the copies
 * had already drifted — `briefingDisplay` used 💧 for WATERFALL where the other four used 💦, and
 * the five did not agree on ordering.
 *
 * <p>The drift is silent by construction: every consumer either filters unknown types out or
 * falls back to the raw enum name, so a missing constant renders as nothing rather than throwing.
 *
 * <p>Keep this in step with `backend/.../entity/LocationType.java`.
 */

/**
 * Display metadata for every backend `LocationType` constant.
 *
 * <p>Order is the display order used everywhere a full list is rendered.
 */
export const LOCATION_TYPE_META = {
  LANDSCAPE: { label: 'Landscape', emoji: '🏔️' },
  WILDLIFE:  { label: 'Wildlife',  emoji: '🐾' },
  SEASCAPE:  { label: 'Seascape',  emoji: '🌊' },
  WOODLAND:  { label: 'Woodland',  emoji: '🌳' },
  WATERFALL: { label: 'Waterfall', emoji: '💦' },
  BLUEBELL:  { label: 'Bluebell',  emoji: '🌸' },
};

/** Every type, in display order. */
export const LOCATION_TYPES = Object.keys(LOCATION_TYPE_META);

/**
 * Types shown as a permanent chip or badge.
 *
 * <p>BLUEBELL is deliberately absent, and this is the one place the distinction is enforced: a
 * bluebell site is a seasonal *subject*, not a kind of place, so a year-round badge would assert
 * a display that is only true for a few weeks. The map already treats it that way — it renders a
 * BLUEBELL filter chip only while `seasonalFeatures` reports the bloom is on. WOODLAND is present
 * because it is a structural, year-round fact about the site.
 *
 * <p>BLUEBELL still has metadata above, so it gets a proper label wherever it *is* surfaced (the
 * map's seasonal chip, the filter summary) rather than reading as a raw enum name.
 */
export const DISPLAY_TYPES = ['LANDSCAPE', 'WILDLIFE', 'SEASCAPE', 'WOODLAND', 'WATERFALL'];

/**
 * Types whose subject is the sky, and which may therefore go to the sky prompt.
 *
 * <p>Mirrors `LocationEntity.hasColourTypes()`. WOODLAND is deliberately ABSENT: a location under
 * a canopy has no sky to forecast. The backend records why — #347 added WOODLAND to that
 * predicate and silently undid V132, putting the woods straight back into the fiery-sky prompt on
 * clear mornings.
 */
export const SKY_SUBJECT_TYPES = ['LANDSCAPE', 'SEASCAPE', 'WATERFALL'];

/** Emoji-only lookup, for the compact grid rows that have no room for a label. */
export const LOCATION_TYPE_ICONS = Object.fromEntries(
  Object.entries(LOCATION_TYPE_META).map(([type, { emoji }]) => [type, emoji]),
);

/**
 * The icons for a location's types, in display order, as one string.
 *
 * <p>`locationType` is an ARRAY, and the briefing rows used to index `LOCATION_TYPE_ICONS` with it
 * directly. A single-typed location got away with it — `['LANDSCAPE']` coerces to the string
 * `'LANDSCAPE'`, which is a real key — but a multi-typed one produced `'LANDSCAPE,BLUEBELL'`,
 * matched nothing, and rendered NO icon at all. So the locations carrying the most information
 * about themselves were the only ones showing none of it, which read as data missing from the
 * location rather than a lookup missing a join.
 *
 * <p>Drawn from {@link DISPLAY_TYPES}, so BLUEBELL is deliberately absent: it is a seasonal
 * subject, not a kind of place, and a year-round 🌸 would assert a bloom in August. An open fell
 * that happens to carry bluebells therefore reads 🏔️ — which is also the honest answer to "why is
 * this in an August briefing at all": because it is a landscape location every day of the year.
 *
 * @param {string[]|string|null|undefined} types the location's `locationType`
 * @returns {string} the icons, or '' when none apply
 */
export function locationTypeIcons(types) {
  const list = Array.isArray(types) ? types : (types == null ? [] : [types]);
  return DISPLAY_TYPES.filter((t) => list.includes(t))
    .map((t) => LOCATION_TYPE_ICONS[t])
    .join('');
}

/**
 * Location name → its {@code locationType}, from the enabled-locations roster.
 *
 * <p>Written twice before this existed — once for the regional planner's grid icons and once for
 * the drill-down's type control — from the same prop, with the same body.
 * Two copies of a join is how the five copies this module replaced started.
 *
 * <p>The value is passed through unchanged rather than normalised to an array, because the two
 * consumers disagree about the shape they want and both already cope: {@link locationTypeIcons}
 * and {@code spotTypes} each accept a bare string or an array.
 *
 * @param {Array} locations the enabled-locations roster, or null
 * @returns {Map<string, string[]|string>} name → its types
 */
export function buildLocationTypeMap(locations) {
  const map = new Map();
  for (const location of locations || []) {
    if (location?.name && location.locationType) map.set(location.name, location.locationType);
  }
  return map;
}

/**
 * Human label for a type, falling back to the raw enum name.
 *
 * <p>The fallback is deliberate: a backend constant this build has never heard of should read as
 * an unfamiliar word rather than vanish, so the gap is visible instead of silent.
 *
 * @param {string} type - a `LocationType` enum name.
 * @returns {string} the display label, or the input unchanged when unknown.
 */
export function locationTypeLabel(type) {
  return LOCATION_TYPE_META[type]?.label ?? type;
}

/**
 * Whether a location may be sent to the SKY prompt (the sunrise/sunset colour forecast).
 *
 * <p>Mirrors `LocationEntity.hasColourTypes()`, the gate `ForecastCommandExecutor`,
 * `ForceSubmitBatchService`, `ModelTestService` and `PromptTestService` all filter on — including
 * its rule that an untyped location counts.
 *
 * <p>Not interchangeable with the briefing's own candidacy test: "may this go to the sky prompt?"
 * and "is this a candidate for the briefing?" have different answers for a wood, and conflating
 * them is the bug the backend records at `LocationEntity.hasColourTypes()`.
 *
 * @param {string[]|null|undefined} types - the location's `locationType` array.
 * @returns {boolean} true when the location has a sky subject.
 */
export function isSkyPromptCandidate(types) {
  const list = types ?? [];
  if (list.length === 0) return true;
  return list.some((t) => SKY_SUBJECT_TYPES.includes(t));
}
