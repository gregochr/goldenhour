package com.gregochr.goldenhour.model;

import java.time.ZonedDateTime;

/**
 * Real-time solar wind measurement from the NOAA ACE/DSCOVR satellite.
 *
 * <p>The Bz component is the most important aurora indicator — negative Bz
 * couples energy into Earth's magnetosphere and drives geomagnetic activity.
 *
 * @param timestamp      when the measurement was taken
 * @param bzNanoTesla    north-south magnetic field component (negative = favourable for aurora)
 * @param speedKmPerSec  solar wind speed (background ~400 km/s, elevated >500 km/s)
 * @param densityPerCm3  proton density per cubic centimetre
 */
public record SolarWindReading(
        ZonedDateTime timestamp,
        double bzNanoTesla,
        double speedKmPerSec,
        double densityPerCm3) {}
