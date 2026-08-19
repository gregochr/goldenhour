package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.BriefingRegionSnapshotEntity;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.BriefingRegionSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingRegionSnapshotService} — the run-to-run movement sink.
 *
 * <p>The failure modes this guards are all silent. A delta computed against the build being served
 * reads zero everywhere; a null mean written as a zero manufactures a large fictitious movement on
 * the next build; a repository failure on the serve path would take the whole briefing down; and
 * binary subtraction of two 1dp decimals publishes a fifteen-digit number behind a chip reading
 * {@code ▲0.6}. Each has its own test, and each asserts the exact value rather than a type.
 */
@ExtendWith(MockitoExtension.class)
class BriefingRegionSnapshotServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 19);
    private static final LocalDateTime DAWN = LocalDateTime.of(DAY, LocalTime.of(5, 30));
    private static final LocalDateTime BUILD = LocalDateTime.of(2026, 8, 19, 14, 0);
    private static final LocalDateTime EARLIER_BUILD = LocalDateTime.of(2026, 8, 19, 4, 0);
    private static final Instant NOW = Instant.parse("2026-08-19T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private BriefingRegionSnapshotRepository repository;

    private BriefingRegionSnapshotService service() {
        return new BriefingRegionSnapshotService(repository, CLOCK);
    }

    /** An open-sky slot with no rating — it votes, and contributes nothing to the mean. */
    private static BriefingSlot sky(String name) {
        return new BriefingSlot(name, DAWN, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null);
    }

    /** An open-sky slot the batch has scored — it votes. */
    private static BriefingSlot scoredSky(String name) {
        return new BriefingSlot(name, DAWN, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null).withClaudeScores(4, 70, 60, "Colour likely.");
    }

    /** A canopy slot the bluebell prompt has scored — it never votes. */
    private static BriefingSlot scoredWood(String name) {
        return BriefingSlot.canopySlot(name, DAWN, Verdict.GO, null, List.of(), null)
                .withClaudeScores(5, 0, 0, "Bluebells at peak.");
    }

    private static BriefingRegion region(String name, Double mean, List<BriefingSlot> slots) {
        return new BriefingRegion(name, Verdict.GO, "Clear.", List.of(), slots,
                null, null, null, null, null, null,
                DisplayVerdict.WORTH_IT, slots.size(), null, false, null, mean, null);
    }

    private static BriefingEventSummary summary(TargetType type, List<BriefingRegion> regions) {
        return new BriefingEventSummary(type, regions, List.of(), DAWN, null);
    }

    private static List<BriefingDay> oneDay(TargetType type, BriefingRegion... regions) {
        return List.of(new BriefingDay(DAY, List.of(
                summary(type, List.of(regions)))));
    }

    @Nested
    @DisplayName("delta — the arithmetic behind the chip")
    class Delta {

        @Test
        void two_one_dp_means_subtract_to_one_dp_not_to_binary_noise() {
            // 3.7 - 3.1 is 0.5999999999999996 as a double. The chip prints one decimal, so the
            // published number must be the one the chip shows or the payload contradicts it.
            assertThat(BriefingRegionSnapshotService.delta(3.7, 3.1)).isEqualTo(0.6);
        }

        @Test
        void a_fall_is_published_as_a_negative() {
            assertThat(BriefingRegionSnapshotService.delta(2.4, 2.7)).isEqualTo(-0.3);
        }

        @Test
        void an_unchanged_region_publishes_a_measured_zero_never_null() {
            // The whole point of the third state: "did not move" is an answer, and the strip marks
            // it with `—`. Nulling it would make it indistinguishable from "no previous build".
            assertThat(BriefingRegionSnapshotService.delta(3.2, 3.2)).isEqualTo(0.0);
        }

        @Test
        void a_null_current_side_yields_no_delta() {
            assertThat(BriefingRegionSnapshotService.delta(null, 3.1)).isNull();
        }

        @Test
        void a_null_previous_side_yields_no_delta() {
            assertThat(BriefingRegionSnapshotService.delta(3.7, null)).isNull();
        }
    }

    @Nested
    @DisplayName("key — the join both sides are built from")
    class Key {

        @Test
        void the_key_is_the_region_name_verbatim() {
            assertThat(BriefingRegionSnapshotService.key("North East", DAY, "SUNSET"))
                    .isEqualTo("North East|2026-08-19|SUNSET");
        }

        @Test
        void a_leading_space_is_not_trimmed_away() {
            // Nothing on either side normalises the region name — the same string is the heat
            // field's focus key. A trim here would make one side of the join stop matching.
            assertThat(BriefingRegionSnapshotService.key(" North East", DAY, "SUNSET"))
                    .isEqualTo(" North East|2026-08-19|SUNSET");
        }
    }

    @Nested
    @DisplayName("record — one row per region, date and event")
    class Record {

        @Test
        void a_scored_region_is_written_with_the_mean_the_payload_publishes() {
            service().record(BUILD, oneDay(TargetType.SUNSET,
                    region("North East", 3.7, List.of(scoredSky("Bamburgh")))));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BriefingRegionSnapshotEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(repository).saveAll(captor.capture());
            List<BriefingRegionSnapshotEntity> rows = captor.getValue();
            assertThat(rows).hasSize(1);
            BriefingRegionSnapshotEntity row = rows.getFirst();
            assertThat(row.getRegionName()).as("region").isEqualTo("North East");
            assertThat(row.getEvaluationDate()).as("date").isEqualTo(DAY);
            assertThat(row.getTargetType()).as("event").isEqualTo("SUNSET");
            assertThat(row.getMeanRating()).as("mean").isEqualByComparingTo(new BigDecimal("3.7"));
            assertThat(row.getVotingCount()).as("voting count").isEqualTo(1);
            assertThat(row.getDisplayVerdict()).as("verdict").isEqualTo("WORTH_IT");
            assertThat(row.getBriefingGeneratedAt()).as("build stamp").isEqualTo(BUILD);
            assertThat(row.getGeneratedAt()).as("write instant").isEqualTo(NOW);
        }

        @Test
        void an_unscored_region_is_written_with_a_null_mean_not_a_zero() {
            // A zero would be a rating, and the next build's delta would read as a 3.7-point
            // collapse rather than as "this region was not scored last time".
            service().record(BUILD, oneDay(TargetType.SUNRISE,
                    region("Cumbria", null, List.of(scoredSky("Wastwater")))));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BriefingRegionSnapshotEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(repository).saveAll(captor.capture());
            assertThat(captor.getValue().getFirst().getMeanRating()).isNull();
        }

        @Test
        void the_voting_count_excludes_a_scored_wood() {
            // The mean is over BriefingSlot.votingSlots, so the count recorded beside it must be
            // over the same population — otherwise a later reader divides one by the other.
            service().record(BUILD, oneDay(TargetType.SUNRISE,
                    region("Northumberland", 4.0,
                            List.of(scoredSky("Bamburgh"), scoredWood("Plessey Woods")))));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BriefingRegionSnapshotEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(repository).saveAll(captor.capture());
            assertThat(captor.getValue().getFirst().getVotingCount()).isEqualTo(1);
        }

        @Test
        void the_voting_count_is_the_RATED_slots_not_the_voting_roster() {
            // ⚠️ The distinction the column exists for. `meanRating` is the mean over the slots
            // BriefingRatingStats actually counted — the rated ones — so recording the roster size
            // beside it answers the opposite of the question the entity's javadoc asks ("a mean
            // over one location or over twelve"). Three voting locations, one rated: the mean is
            // over ONE, and it is unrecoverable afterwards because the payload it came from is
            // overwritten by the next build.
            service().record(BUILD, oneDay(TargetType.SUNSET,
                    region("North East", 5.0,
                            List.of(scoredSky("Bamburgh"), sky("Seahouses"), sky("Craster")))));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BriefingRegionSnapshotEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(repository).saveAll(captor.capture());
            assertThat(captor.getValue().getFirst().getVotingCount()).isEqualTo(1);
        }

        @Test
        void every_region_of_every_event_of_every_day_gets_a_row() {
            List<BriefingDay> days = List.of(
                    new BriefingDay(DAY, List.of(
                            summary(TargetType.SUNRISE, List.of(
                                    region("North East", 3.0, List.of(scoredSky("Bamburgh"))),
                                    region("Cumbria", 2.0, List.of(scoredSky("Wastwater"))))),
                            summary(TargetType.SUNSET, List.of(
                                    region("North East", 4.0, List.of(scoredSky("Bamburgh"))))))),
                    new BriefingDay(DAY.plusDays(1), List.of(
                            summary(TargetType.SUNRISE, List.of(
                                    region("North East", 1.0, List.of(scoredSky("Bamburgh"))))))));

            service().record(BUILD, days);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BriefingRegionSnapshotEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(repository).saveAll(captor.capture());
            // The whole KEY of every row, not the count. A count passes while the writer hardcodes
            // the event or reuses the first day's date — and both of those are silent: the rows
            // land under a key the reader never asks for, the chips are simply absent, and absence
            // is this channel's own documented degrade. The date and the event are varied here
            // precisely because they are the two axes nothing else in this file varies.
            assertThat(captor.getValue())
                    .extracting(BriefingRegionSnapshotEntity::getRegionName,
                            BriefingRegionSnapshotEntity::getEvaluationDate,
                            BriefingRegionSnapshotEntity::getTargetType)
                    .containsExactlyInAnyOrder(
                            tuple("North East", DAY, "SUNRISE"),
                            tuple("Cumbria", DAY, "SUNRISE"),
                            tuple("North East", DAY, "SUNSET"),
                            tuple("North East", DAY.plusDays(1), "SUNRISE"));
        }

        @Test
        void rows_older_than_the_retention_window_are_pruned_by_the_write_instant() {
            service().record(BUILD, oneDay(TargetType.SUNSET,
                    region("North East", 3.7, List.of(scoredSky("Bamburgh")))));

            // Keyed on the WRITE instant, not on the build stamp or the briefed date: retention is
            // a statement about how long the table keeps history, and the other two are forecast
            // quantities a backfill could move.
            //
            // The literal 90 days, NOT `RETENTION` — deriving the expected value from the constant
            // under test makes the assertion hold for any value of it, including 9 and 900. §4.7
            // names 90 as the rule, so 90 is what this pins.
            verify(repository).deleteByGeneratedAtBefore(eq(NOW.minus(Duration.ofDays(90))));
            assertThat(BriefingRegionSnapshotService.RETENTION).isEqualTo(Duration.ofDays(90));
        }

        @Test
        void a_null_build_stamp_writes_nothing() {
            service().record(null, oneDay(TargetType.SUNSET,
                    region("North East", 3.7, List.of(scoredSky("Bamburgh")))));
            verifyNoInteractions(repository);
        }

        @Test
        void an_empty_day_list_writes_nothing_and_prunes_nothing() {
            // Not merely "writes nothing": pruning on a cycle that produced no days would trim the
            // table on the strength of a build that recorded no state of its own.
            service().record(BUILD, List.of());
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("the build stamp is reduced to the column's own precision")
    class StampPrecision {

        /** A stamp with sub-microsecond nanos, as Linux's clock produces and macOS's does not. */
        private static final LocalDateTime NANOS = LocalDateTime.of(2026, 8, 19, 14, 0, 3, 361_003_217);

        @Test
        void the_stored_stamp_is_truncated_to_microseconds() {
            // Postgres TIMESTAMP holds microseconds and pgjdbc rounds half-up on the way in, so a
            // nanosecond that survives to the INSERT makes the stored value differ from the value
            // held in memory. Truncating on the way in is half of what stops a build being compared
            // against itself.
            service().record(NANOS, oneDay(TargetType.SUNSET,
                    region("North East", 3.7, List.of(scoredSky("Bamburgh")))));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BriefingRegionSnapshotEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(repository).saveAll(captor.capture());
            assertThat(captor.getValue().getFirst().getBriefingGeneratedAt())
                    .isEqualTo(LocalDateTime.of(2026, 8, 19, 14, 0, 3, 361_003_000));
        }

        @Test
        void the_comparand_is_truncated_the_same_way() {
            // The other half, and the one that matters: `MAX(stamp) < :current` with an untruncated
            // comparand answers with the CURRENT build's own row, because the stored value lost its
            // nanos and is therefore strictly smaller. Every delta then reads `serveMean −
            // sameBuildMean` → 0.0, and the strip fills with the mark that means "measured zero".
            when(repository.findPreviousBuildStamp(any())).thenReturn(Optional.empty());

            service().previousBuild(NANOS);

            verify(repository).findPreviousBuildStamp(
                    eq(LocalDateTime.of(2026, 8, 19, 14, 0, 3, 361_003_000)));
        }
    }

    @Nested
    @DisplayName("previousBuild — the basis every chip is measured against")
    class Previous {

        @Test
        void the_basis_is_the_newest_build_strictly_before_this_one() {
            when(repository.findPreviousBuildStamp(BUILD)).thenReturn(Optional.of(EARLIER_BUILD));
            when(repository.findByBriefingGeneratedAt(EARLIER_BUILD))
                    .thenReturn(List.of(row("North East", DAY, "SUNSET", new BigDecimal("3.1"))));

            BriefingRegionSnapshotService.PreviousBuild previous = service().previousBuild(BUILD);

            assertThat(previous.generatedAt()).isEqualTo(EARLIER_BUILD);
            assertThat(previous.meanByKey())
                    .containsEntry("North East|2026-08-19|SUNSET", 3.1);
        }

        @Test
        void a_row_with_no_mean_contributes_no_entry() {
            // Absent, not zero: the delta helper wants a null on that side, and an entry mapped to
            // 0.0 would be read as a rating.
            when(repository.findPreviousBuildStamp(BUILD)).thenReturn(Optional.of(EARLIER_BUILD));
            when(repository.findByBriefingGeneratedAt(EARLIER_BUILD))
                    .thenReturn(List.of(row("Cumbria", DAY, "SUNRISE", null)));

            assertThat(service().previousBuild(BUILD).meanByKey()).isEmpty();
        }

        @Test
        void no_earlier_build_yields_the_empty_answer() {
            when(repository.findPreviousBuildStamp(BUILD)).thenReturn(Optional.empty());

            BriefingRegionSnapshotService.PreviousBuild previous = service().previousBuild(BUILD);

            assertThat(previous.isEmpty()).isTrue();
            assertThat(previous.generatedAt()).isNull();
        }

        @Test
        void a_repository_failure_PROPAGATES_because_the_caller_owns_the_catch() {
            // ⚠️ Asserting a throw, and that is the fix rather than a regression. An earlier cut
            // was @Transactional(readOnly = true) with the try/catch inside: a JPA failure marks
            // the transaction rollback-only, so swallowing it only defers the throw to the proxy's
            // commit — past the catch — and 500s GET /api/briefing anyway. A Mockito test of that
            // shape could only ever prove the catch RAN, never that the request survived, because
            // there is no proxy and no transaction in a unit test.
            //
            // The isolation is now in BriefingService.attachMovement, outside the bean boundary,
            // where BriefingServiceTest exercises it against a real call.
            when(repository.findPreviousBuildStamp(BUILD))
                    .thenThrow(new IllegalStateException("connection reset"));

            assertThatThrownBy(() -> service().previousBuild(BUILD))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void a_null_current_stamp_yields_the_empty_answer_without_querying() {
            assertThat(service().previousBuild(null).isEmpty()).isTrue();
            verifyNoInteractions(repository);
        }
    }

    private static BriefingRegionSnapshotEntity row(String regionName, LocalDate date,
            String targetType, BigDecimal mean) {
        BriefingRegionSnapshotEntity entity = new BriefingRegionSnapshotEntity();
        entity.setRegionName(regionName);
        entity.setEvaluationDate(date);
        entity.setTargetType(targetType);
        entity.setMeanRating(mean);
        entity.setVotingCount(1);
        entity.setDisplayVerdict("WORTH_IT");
        entity.setGeneratedAt(NOW);
        entity.setBriefingGeneratedAt(EARLIER_BUILD);
        return entity;
    }
}
