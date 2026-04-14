package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;

import java.time.LocalDate;
import java.util.List;

/**
 * Strategy interface for detecting Hot Topics from existing triage data.
 * Each implementation scans triage output for a specific phenomenon
 * (bluebells, tides, aurora, etc.) and emits zero or more HotTopic records.
 *
 * <p>Detectors run after forecasts have been evaluated — they are read-only
 * consumers of data that has already been fetched and persisted. They must
 * NOT make any external API calls.</p>
 */
public interface HotTopicDetector {

    /**
     * Detects hot topics for the given date range.
     *
     * @param fromDate start of the forecast window (inclusive)
     * @param toDate   end of the forecast window (inclusive)
     * @return list of detected hot topics, never null, may be empty
     */
    List<HotTopic> detect(LocalDate fromDate, LocalDate toDate);
}
