package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.AskReadyAnswerEntity;
import com.gregochr.goldenhour.repository.AskReadyAnswerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes the {@code ask_ready_answer} table (V165): an idempotent upsert on {@code
 * (scope_key, question_id)} and the JSON codec of a stored answer.
 *
 * <p>The codec is this class's own mapper rather than the application's: the stored form must not
 * move if the application mapper's configuration does (dates are ISO text, and a pick keeps its
 * {@code ratingAtAnswer} and {@code verdictAtAnswer}, which the serve-time freshness check needs).
 *
 * <p>A night-bound answer ({@code NIGHT_SKY}) also keeps the night version it was written under, as
 * one more property of the same JSON object, {@value #ASTRO_VERSION}: no column, so no migration. It is
 * stripped before the answer is decoded, and a row without it (every row written before the property
 * existed, and every question that is not night-bound) reads as a null version.
 */
@Service
public class AskReadyStore {

    /** The property of {@code answer_json} that carries a night-bound answer's night version. */
    static final String ASTRO_VERSION = "astroVersion";

    private static final String WINDOW_SEPARATOR = ",";

    private final AskReadyAnswerRepository repository;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Creates the store.
     *
     * @param repository the table
     * @param clock      the application clock, for {@code created_at}
     */
    public AskReadyStore(AskReadyAnswerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * A stored answer, decoded.
     *
     * @param scopeKey            {@code ALL} or a region id as text
     * @param questionId          the Ready question's name
     * @param questionText        the text the answer was written for
     * @param windowIds           the windows the question was asked about
     * @param briefingGeneratedAt when the briefing it was built from was generated (UTC)
     * @param answer              the answer, each pick carrying its rating and verdict at answer time
     * @param astroVersion        the night version ({@link AskNightVersion}) a night-bound answer was
     *                            written under, or null for any other answer
     */
    public record Stored(String scopeKey, String questionId, String questionText,
            List<String> windowIds, LocalDateTime briefingGeneratedAt, AskAnswer answer,
            String astroVersion) {

        /**
         * A stored answer that is not night-bound.
         *
         * @param scopeKey            {@code ALL} or a region id as text
         * @param questionId          the Ready question's name
         * @param questionText        the text the answer was written for
         * @param windowIds           the windows the question was asked about
         * @param briefingGeneratedAt when the briefing it was built from was generated (UTC)
         * @param answer              the answer
         */
        public Stored(String scopeKey, String questionId, String questionText, List<String> windowIds,
                LocalDateTime briefingGeneratedAt, AskAnswer answer) {
            this(scopeKey, questionId, questionText, windowIds, briefingGeneratedAt, answer, null);
        }
    }

    /**
     * Writes an answer that is not night-bound, replacing the scope's row for that question if there
     * is one.
     *
     * @param scopeKey            {@code ALL} or a region id as text
     * @param question            the Ready question
     * @param offer               what it was asked under (its text and windows are stored)
     * @param answer              the validated answer
     * @param briefingGeneratedAt when the briefing was generated (UTC)
     * @param pipelineRunId       the triggering pipeline run, or null for an on-demand precompute
     * @throws IllegalStateException if the answer cannot be serialised
     */
    @Transactional
    public void upsert(String scopeKey, ReadyQuestion question, ReadyQuestion.Offer offer,
            AskAnswer answer, LocalDateTime briefingGeneratedAt, Long pipelineRunId) {
        upsert(scopeKey, question, offer, answer, briefingGeneratedAt, pipelineRunId, null);
    }

    /**
     * Writes an answer, replacing the scope's row for that question if there is one.
     *
     * @param scopeKey            {@code ALL} or a region id as text
     * @param question            the Ready question
     * @param offer               what it was asked under (its text and windows are stored)
     * @param answer              the validated answer
     * @param briefingGeneratedAt when the briefing was generated (UTC)
     * @param pipelineRunId       the triggering pipeline run, or null for an on-demand precompute
     * @param astroVersion        the night version a night-bound answer was written under
     *                            ({@link ReadyQuestion#nightVersion}), or null for any other answer
     * @throws IllegalStateException if the answer cannot be serialised
     */
    @Transactional
    public void upsert(String scopeKey, ReadyQuestion question, ReadyQuestion.Offer offer,
            AskAnswer answer, LocalDateTime briefingGeneratedAt, Long pipelineRunId, String astroVersion) {
        AskReadyAnswerEntity row = repository
                .findByScopeKeyAndQuestionId(scopeKey, question.name())
                .orElseGet(AskReadyAnswerEntity::new);
        row.setScopeKey(scopeKey);
        row.setQuestionId(question.name());
        row.setQuestionText(offer.text());
        row.setWindowIds(String.join(WINDOW_SEPARATOR, offer.windowIds()));
        row.setBriefingGeneratedAt(briefingGeneratedAt);
        row.setPipelineRunId(pipelineRunId);
        row.setAnswerJson(toJson(answer, astroVersion));
        row.setCreatedAt(clock.instant());
        repository.save(row);
    }

    /**
     * Every stored answer of a scope. A row that cannot be decoded (an unknown question id, or JSON
     * that no longer reads) is dropped, never served and never thrown: a serve must not fail
     * because one row is bad.
     *
     * @param scopeKey {@code ALL} or a region id as text
     * @return the decoded rows
     */
    @Transactional(readOnly = true)
    public List<Stored> findScope(String scopeKey) {
        return repository.findByScopeKey(scopeKey).stream()
                .map(this::decode)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<Stored> decode(AskReadyAnswerEntity row) {
        try {
            JsonNode tree = mapper.readTree(row.getAnswerJson());
            String astroVersion = tree.hasNonNull(ASTRO_VERSION) ? tree.get(ASTRO_VERSION).asText() : null;
            ((ObjectNode) tree).remove(ASTRO_VERSION);
            AskAnswer answer = mapper.treeToValue(tree, AskAnswer.class);
            List<String> windows = row.getWindowIds() == null || row.getWindowIds().isBlank()
                    ? List.of() : List.of(row.getWindowIds().split(WINDOW_SEPARATOR));
            return Optional.of(new Stored(row.getScopeKey(), row.getQuestionId(),
                    row.getQuestionText(), windows, row.getBriefingGeneratedAt(), answer, astroVersion));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private String toJson(AskAnswer answer, String astroVersion) {
        try {
            ObjectNode tree = mapper.valueToTree(answer);
            if (astroVersion != null) {
                tree.put(ASTRO_VERSION, astroVersion);
            }
            return mapper.writeValueAsString(tree);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("A Ready answer could not be serialised", e);
        }
    }
}
