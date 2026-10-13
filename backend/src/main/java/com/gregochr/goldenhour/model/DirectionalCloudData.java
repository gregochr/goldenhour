package com.gregochr.goldenhour.model;

/**
 * Cloud cover sampled at the solar and antisolar horizon points, 113 km from the observer.
 *
 * <p>The solar horizon is the direction of the sun (west for sunset, east for sunrise).
 * The antisolar horizon is the opposite direction. Sampling at offset points allows
 * directional cloud assessment that the single-point observer data cannot provide.
 *
 * <p>{@code farSolarLowCloudPercent} is sampled at 226 km (2× the horizon distance) along
 * the solar azimuth. Comparing it to {@code solarLowCloudPercent} reveals whether high solar
 * horizon low cloud is a thin strip (drops sharply beyond the horizon) or an extensive blanket
 * (stays high). May be {@code null} if the additional fetch fails.
 *
 * <p>The five trailing far-point components are recorded for later analysis and are never
 * prompted: no prompt builder and no triage rule reads them.
 *
 * <p>The dark-side slot is adjacent to the stored slot. When {@code TimeSlotUtils.findBestIndex}
 * had to fall back (no slot on the event's preferred side, for example an event outside the hourly
 * array), the stored slot and its dark-side neighbour are both away from the event.
 *
 * @param solarLowCloudPercent      low cloud (0-3 km) at the solar horizon point (113 km)
 * @param solarMidCloudPercent      mid cloud (3-8 km) at the solar horizon point (113 km)
 * @param solarHighCloudPercent     high cloud (8+ km) at the solar horizon point (113 km)
 * @param antisolarLowCloudPercent  low cloud (0-3 km) at the antisolar horizon point (113 km)
 * @param antisolarMidCloudPercent  mid cloud (3-8 km) at the antisolar horizon point (113 km)
 * @param antisolarHighCloudPercent high cloud (8+ km) at the antisolar horizon point (113 km)
 * @param farSolarLowCloudPercent   low cloud (0-3 km) at 226 km along the solar azimuth,
 *                                  or {@code null} if unavailable
 * @param farSolarMidCloudPercent   mid cloud (3-8 km) at 226 km along the solar azimuth at the
 *                                  event's stored slot; recorded, never prompted
 *                                  (far-cloud-lid-plan P2); {@code null} if unavailable
 * @param farSolarHighCloudPercent  high cloud (8+ km) at 226 km along the solar azimuth at the
 *                                  event's stored slot; recorded, never prompted
 *                                  (far-cloud-lid-plan P2); {@code null} if unavailable
 * @param farSolarLowCloudPercentDarkHour  low cloud at 226 km at the dark-side hour; recorded,
 *                                  never prompted (far-cloud-lid-plan P2). The hourly slot on the
 *                                  dark side of the event: the one after the stored slot for a
 *                                  sunset, the one before it for a sunrise; {@code null} if
 *                                  unavailable
 * @param farSolarMidCloudPercentDarkHour  mid cloud at 226 km at the dark-side hour; recorded,
 *                                  never prompted (far-cloud-lid-plan P2). The hourly slot on the
 *                                  dark side of the event: the one after the stored slot for a
 *                                  sunset, the one before it for a sunrise; {@code null} if
 *                                  unavailable
 * @param farSolarHighCloudPercentDarkHour high cloud at 226 km at the dark-side hour; recorded,
 *                                  never prompted (far-cloud-lid-plan P2). The hourly slot on the
 *                                  dark side of the event: the one after the stored slot for a
 *                                  sunset, the one before it for a sunrise; {@code null} if
 *                                  unavailable
 */
public record DirectionalCloudData(
        int solarLowCloudPercent,
        int solarMidCloudPercent,
        int solarHighCloudPercent,
        int antisolarLowCloudPercent,
        int antisolarMidCloudPercent,
        int antisolarHighCloudPercent,
        Integer farSolarLowCloudPercent,
        Integer farSolarMidCloudPercent,
        Integer farSolarHighCloudPercent,
        Integer farSolarLowCloudPercentDarkHour,
        Integer farSolarMidCloudPercentDarkHour,
        Integer farSolarHighCloudPercentDarkHour) {

    /**
     * Creates directional cloud data without the recorded far-point mid, high and dark-hour
     * values, which are all left {@code null}.
     *
     * @param solarLowCloudPercent      low cloud at the solar horizon point (113 km)
     * @param solarMidCloudPercent      mid cloud at the solar horizon point (113 km)
     * @param solarHighCloudPercent     high cloud at the solar horizon point (113 km)
     * @param antisolarLowCloudPercent  low cloud at the antisolar horizon point (113 km)
     * @param antisolarMidCloudPercent  mid cloud at the antisolar horizon point (113 km)
     * @param antisolarHighCloudPercent high cloud at the antisolar horizon point (113 km)
     * @param farSolarLowCloudPercent   low cloud at 226 km along the solar azimuth, or
     *                                  {@code null} if unavailable
     */
    public DirectionalCloudData(
            int solarLowCloudPercent,
            int solarMidCloudPercent,
            int solarHighCloudPercent,
            int antisolarLowCloudPercent,
            int antisolarMidCloudPercent,
            int antisolarHighCloudPercent,
            Integer farSolarLowCloudPercent) {
        this(solarLowCloudPercent, solarMidCloudPercent, solarHighCloudPercent,
                antisolarLowCloudPercent, antisolarMidCloudPercent, antisolarHighCloudPercent,
                farSolarLowCloudPercent, null, null, null, null, null);
    }
}
