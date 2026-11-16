package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.service.evaluation.EvaluationTask.Forecast.PromptKind;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Measurement-only equivalence keys for the P2a shadow log (see {@code triage-cost-cut-plan.md}).
 *
 * <p>Two sky requests are <em>equivalent</em> when they would be identical apart from the place name
 * (only the leading {@code Location: <name>.} prefix of the user message is neutralised). The
 * <em>strict</em> key requires the rendered event time to match exactly; the <em>minute</em> key
 * truncates that rendered time to the minute. Nothing here changes a request: the keys are hashed
 * and only ever logged.
 */
final class BatchShareKeys {

    /** Separator between key-material fields; cannot appear in any of them. */
    private static final char SEP = '\u001f';

    private BatchShareKeys() {
    }

    /**
     * The two equivalence keys of one eligible task, as SHA-256 hex.
     *
     * @param strict the key requiring an identical rendered event time
     * @param minute the key with the event time truncated to the minute
     */
    record Keys(String strict, String minute) {
    }

    /**
     * One sky task as seen by the shadow measurement.
     *
     * @param locationId   the place id
     * @param locationName the place name
     * @param keys         its keys, or null when the task is not eligible to share
     */
    record Candidate(Long locationId, String locationName, Keys keys) {
    }

    /**
     * Counts for one bucket, plus the groups of two or more behind them.
     *
     * @param tasks           sky tasks in the bucket
     * @param eligible        tasks that could share
     * @param strictShareable calls a one-per-group scheme would save on the strict key
     * @param minuteShareable calls it would save on the minute key
     * @param strictGroups    strict groups of two or more
     * @param minuteGroups    minute groups of two or more
     */
    record Summary(int tasks, int eligible, int strictShareable, int minuteShareable,
            List<List<Candidate>> strictGroups, List<List<Candidate>> minuteGroups) {
    }

    /**
     * Everything about one task's request that the keys are built from.
     *
     * @param promptKind     the task's prompt kind
     * @param forced         whether it is a forced task
     * @param evalRowId      its eval row id, may be null
     * @param locationName   the name the message was rendered with
     * @param date           target date
     * @param event          target event
     * @param eventTime      the solar event time rendered into the message, may be null
     * @param modelId        the model id
     * @param maxTokens      the request's max tokens
     * @param modelSettings  the thinking and effort settings the request carries
     * @param cachePrefixKey the request's cache prefix key
     * @param userMessage    the rendered user message
     */
    record Input(PromptKind promptKind, boolean forced, Long evalRowId, String locationName,
            Object date, Object event, LocalDateTime eventTime, String modelId, int maxTokens,
            String modelSettings, String cachePrefixKey, String userMessage) {
    }

    /**
     * Computes the keys for a task, or empty when the task is not eligible (not sky lane, forced,
     * or no eval row). A message that does not open with the expected {@code Location:} prefix gets
     * a unique key, so it never shares.
     *
     * @param in the request facts
     * @return the keys, or empty when ineligible
     */
    static Optional<Keys> keysFor(Input in) {
        if (in.promptKind() != PromptKind.SKY || in.forced() || in.evalRowId() == null) {
            return Optional.empty();
        }
        String prefix = "Location: " + in.locationName() + ". ";
        if (in.locationName() == null || in.userMessage() == null
                || !in.userMessage().startsWith(prefix)) {
            String unique = sha256("unique" + SEP + in.evalRowId());
            return Optional.of(new Keys(unique, unique));
        }
        String rest = in.userMessage().substring(prefix.length());
        String head = String.join(String.valueOf(SEP), String.valueOf(in.date()),
                String.valueOf(in.event()), String.valueOf(in.modelId()),
                String.valueOf(in.maxTokens()), String.valueOf(in.modelSettings()),
                String.valueOf(in.cachePrefixKey()));
        String neutral = head + SEP + "Location: <NAME>. ";
        return Optional.of(new Keys(sha256(neutral + rest),
                sha256(neutral + truncateEventTime(rest, in.event(), in.eventTime()))));
    }

    /**
     * Truncates the rendered event time inside the message remainder to the minute. The remainder
     * must open with {@code <event>: <time> UTC.}; if it does not, it is returned unchanged (so the
     * minute key falls back to the strict key).
     */
    private static String truncateEventTime(String rest, Object event, LocalDateTime eventTime) {
        if (eventTime == null) {
            return rest;
        }
        String expected = event + ": " + eventTime + " UTC.";
        if (!rest.startsWith(expected)) {
            return rest;
        }
        return event + ": " + eventTime.truncatedTo(ChronoUnit.MINUTES) + " UTC."
                + rest.substring(expected.length());
    }

    /**
     * Groups candidates and counts what a one-per-group scheme would save.
     *
     * @param candidates every sky task in one bucket
     * @return the summary
     */
    static Summary summarise(List<Candidate> candidates) {
        Map<String, List<Candidate>> strict = new LinkedHashMap<>();
        Map<String, List<Candidate>> minute = new LinkedHashMap<>();
        int eligible = 0;
        for (Candidate c : candidates) {
            if (c.keys() == null) {
                continue;
            }
            eligible++;
            strict.computeIfAbsent(c.keys().strict(), k -> new ArrayList<>()).add(c);
            minute.computeIfAbsent(c.keys().minute(), k -> new ArrayList<>()).add(c);
        }
        List<List<Candidate>> strictGroups = multiples(strict);
        List<List<Candidate>> minuteGroups = multiples(minute);
        return new Summary(candidates.size(), eligible, saved(strictGroups), saved(minuteGroups),
                strictGroups, minuteGroups);
    }

    private static List<List<Candidate>> multiples(Map<String, List<Candidate>> groups) {
        return groups.values().stream().filter(g -> g.size() >= 2).toList();
    }

    private static int saved(List<List<Candidate>> groups) {
        return groups.stream().mapToInt(g -> g.size() - 1).sum();
    }

    private static String sha256(String material) {
        return PromptHash.sha256Hex(material);
    }
}
