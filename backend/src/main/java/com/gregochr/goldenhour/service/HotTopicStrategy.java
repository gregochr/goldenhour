package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;

import java.time.LocalDate;
import java.util.List;

/**
 * Strategy interface for detecting Hot Topics from existing triage data.
 * Each implementation scans triage output for a specific phenomenon
 * (bluebells, tides, aurora, etc.) and emits zero or more HotTopic records.
 *
 * <p>Strategies run AFTER the triage cycle completes — they are read-only
 * consumers of data that has already been fetched. They must NOT make
 * any external API calls.</p>
 */
public interface HotTopicStrategy {

    /**
     * Detects hot topics for the given date range.
     *
     * @param fromDate start of the forecast window (inclusive)
     * @param toDate   end of the forecast window (inclusive)
     * @return list of detected hot topics, never null, may be empty
     */
    List<HotTopic> detect(LocalDate fromDate, LocalDate toDate);

    /**
     * Detects hot topics reading the pass's shared inputs instead of querying for its own.
     *
     * <p>{@code HotTopicAggregator} calls this form with one {@link HotTopicInputs} per serve, so
     * the slot signals and the location rosters are each read once however many strategies need
     * them. A strategy that reads either overrides this and has its two-argument {@code detect}
     * build a fresh {@code HotTopicInputs} from its own repositories and delegate here; the default
     * serves a strategy that reads neither.
     *
     * @param fromDate start of the forecast window (inclusive)
     * @param toDate   end of the forecast window (inclusive)
     * @param inputs   the pass's shared inputs
     * @return list of detected hot topics, never null, may be empty
     */
    default List<HotTopic> detect(LocalDate fromDate, LocalDate toDate, HotTopicInputs inputs) {
        return detect(fromDate, toDate);
    }
}
