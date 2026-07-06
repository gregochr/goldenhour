package com.gregochr.goldenhour.model;

import java.time.Instant;

/**
 * A single noctilucent-cloud observer report parsed from the NLCNET sightings page.
 *
 * <p>This is raw scraped data — a REACTIVE community report, not a forecast. The
 * {@link com.gregochr.goldenhour.service.NlcSightingService} decides whether a report
 * is fresh and relevant enough to surface as a live banner.
 *
 * @param observer      the observer's name, e.g. {@code "Alan C Tough"}; may be blank
 * @param location      the observer's place as reported, e.g. {@code "Elgin, Scotland"};
 *                      never blank (a report with no location is discarded during parsing)
 * @param reportedAt    the UTC instant the sighting was observed, derived from the report's
 *                      date and start time; never null
 */
public record NlcSightingReport(String observer, String location, Instant reportedAt) {
}
