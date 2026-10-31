package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * One location whose WorldTides fetch showed a change worth telling an admin about: stored tide
 * times or heights moved, or the atlas, station or response point behind them did.
 *
 * @param locationName   the location's name
 * @param sourceChange   what changed in the source (before/after atlas, station, response point), or
 *                       null when only the stored values moved
 * @param timingExamples the largest before/after pairs, one line each; empty when only the source moved
 */
public record TideSourceChange(String locationName, String sourceChange, List<String> timingExamples) {
}
