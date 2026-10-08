package com.gregochr.goldenhour.service.ask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serves the stored Ready answers (plan §2.4): the ones still true against live data, whole or not
 * at all ({@link AskReadyFreshness}), re-decorated from the live snapshot. Written by
 * {@link AskReadyPrecompute}; the two share only the store and the snapshot builder.
 *
 * <p>Relevance is re-applied at serve time through the same predicate the precompute applies at
 * store time ({@link ReadyRelevance}), so a stored row can never carry what a fresh one could not.
 */
@Service
public class AskReadyServing {

    private static final Logger LOG = LoggerFactory.getLogger(AskReadyServing.class);

    /** How many other questions an answer suggests. */
    private static final int TRY_COUNT = 2;

    private final AskSnapshotBuilder snapshotBuilder;
    private final AskReadyStore store;

    /**
     * Creates the service.
     *
     * @param snapshotBuilder builds the live snapshot freshness is checked against
     * @param store           the {@code ask_ready_answer} table
     */
    public AskReadyServing(AskSnapshotBuilder snapshotBuilder, AskReadyStore store) {
        this.snapshotBuilder = snapshotBuilder;
        this.store = store;
    }

    /**
     * The Ready questions of a scope that are still true, in catalogue order.
     *
     * @param scope {@link AskScope#ALL} or one region
     * @return the fresh questions; empty when there is no briefing or none is fresh
     */
    public AskReadyResponse serve(AskScope scope) {
        String echo = scope.isEverywhere() ? "all" : scope.key();
        Optional<AskSnapshot> live = snapshotBuilder.current();
        if (live.isEmpty()) {
            return new AskReadyResponse(echo, List.of());
        }
        return new AskReadyResponse(echo, freshAnswers(scope, live.get()));
    }

    /**
     * The Ready questions of a scope that are still true against a snapshot the caller already holds,
     * each decorated exactly as {@link #serve} serves it. The typed question's intent match uses this,
     * so a Ready answer given to a typed question is the very object, with the very freshness test,
     * a tap on the same question would have got.
     *
     * @param scope {@link AskScope#ALL} or one region
     * @param live  the live snapshot the freshness check is made against
     * @return the fresh questions in catalogue order; empty when none is fresh
     */
    public List<AskReadyResponse.Question> freshAnswers(AskScope scope, AskSnapshot live) {
        List<Fresh> fresh = freshQuestions(scope, live);
        List<AskReadyResponse.Question> questions = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) {
            questions.add(toQuestion(fresh, i));
        }
        return questions;
    }

    /**
     * Up to {@code limit} Ready questions of a scope that are fresh against the given snapshot, in
     * catalogue order: the {@code try} suggestions of a typed answer that could not answer (plan
     * §2.9). The same freshness test {@link #serve} applies, so a suggestion is always one the client
     * will find in its Ready list.
     *
     * @param scope {@link AskScope#ALL} or one region
     * @param live  the live snapshot the freshness check is made against
     * @param limit the most suggestions to return
     * @return the suggestions; empty when none is fresh
     */
    public List<AskReadyResponse.Suggestion> suggestions(AskScope scope, AskSnapshot live, int limit) {
        return freshQuestions(scope, live).stream().limit(Math.max(0, limit))
                .map(f -> new AskReadyResponse.Suggestion(f.question().name(), f.stored().questionText()))
                .toList();
    }

    /** The stored questions of a scope that are still true against {@code live}, in catalogue order. */
    private List<Fresh> freshQuestions(AskScope scope, AskSnapshot live) {
        Map<String, AskReadyStore.Stored> byId = new LinkedHashMap<>();
        store.findScope(scope.key()).forEach(s -> byId.put(s.questionId(), s));

        List<Fresh> fresh = new ArrayList<>();
        for (ReadyQuestion question : ReadyQuestion.values()) {
            AskReadyStore.Stored stored = byId.get(question.name());
            if (stored == null) {
                continue;
            }
            AskReadyFreshness.Verdict verdict =
                    AskReadyFreshness.check(question, stored, live, scope);
            if (verdict.fresh()) {
                fresh.add(new Fresh(question, stored, verdict.answer()));
            } else {
                LOG.debug("[ASK] Ready {}/{} withheld: {}", scope.key(), question, verdict.reason());
            }
        }
        return fresh;
    }

    private record Fresh(ReadyQuestion question, AskReadyStore.Stored stored, AskAnswer answer) {
    }

    /** Builds the wire question at {@code index}; its suggestions are the next fresh others, wrapping. */
    private static AskReadyResponse.Question toQuestion(List<Fresh> fresh, int index) {
        Fresh self = fresh.get(index);
        List<AskReadyResponse.Suggestion> suggestions = new ArrayList<>();
        for (int step = 1; step < fresh.size() && suggestions.size() < TRY_COUNT; step++) {
            Fresh other = fresh.get((index + step) % fresh.size());
            suggestions.add(new AskReadyResponse.Suggestion(other.question().name(),
                    other.stored().questionText()));
        }
        AskAnswer answer = self.answer();
        AskReadyResponse.Answer wire = new AskReadyResponse.Answer(true, "ready", answer.summary(),
                answer.picks().stream().map(AskReadyResponse.Pick::of).toList(), answer.events(),
                null, suggestions);
        // Each question carries its own build time and label: rows of one scope can come from
        // different precomputes (a question whose run failed keeps its earlier answer).
        return new AskReadyResponse.Question(self.question().name(), self.stored().questionText(),
                self.question().tabs(), self.stored().briefingGeneratedAt(),
                AskClock.londonHHmm(self.stored().briefingGeneratedAt()), wire);
    }
}
