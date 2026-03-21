package com.gregochr.goldenhour.model;

import java.time.ZonedDateTime;

/**
 * NOAA OVATION aurora probability at a given latitude.
 *
 * @param forecastTime          when this OVATION nowcast was produced
 * @param probabilityAtLatitude aurora probability (0–100) at the queried latitude
 * @param latitude              the latitude used for the lookup
 */
public record OvationReading(ZonedDateTime forecastTime, double probabilityAtLatitude, double latitude) {}
