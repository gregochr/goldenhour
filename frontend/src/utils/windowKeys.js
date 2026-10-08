/**
 * The two window-key formats the client uses, each defined once.
 *
 * <p>⚠️ <b>They are different on purpose and are NOT merged.</b> {@link windowKey} ({@code
 * date:targetType}) addresses a window as a value — a card's key, a point-set key, the tide
 * strip's per-window map. {@link windowTail} ({@code date|targetType}) is the tail of a per-location
 * index key ({@code `${locationId}|${tail}`}); the {@code |} form is baked into those keys and is
 * built by hand in a few tests, so it must not drift to the colon form.
 */

/**
 * The key a solar window goes by wherever a window is addressed as a value
 * ({@code card.key}, {@code buildHeatPointSets}, {@code buildWindowTideIndex}, {@code tideByWindow}).
 *
 * @param {string} date       ISO date
 * @param {string} targetType 'SUNRISE' | 'SUNSET'
 * @returns {string} {@code date:targetType}
 */
export function windowKey(date, targetType) {
  return `${date}:${targetType}`;
}

/**
 * The window half of every per-location index key and of {@code latestSolarEventTimes}' keys.
 *
 * @param {string} date       ISO date
 * @param {string} targetType 'SUNRISE' | 'SUNSET'
 * @returns {string} {@code date|targetType}
 */
export function windowTail(date, targetType) {
  return `${date}|${targetType}`;
}
