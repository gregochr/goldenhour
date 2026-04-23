package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingRatingStatsTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 23);
    private static final TargetType EVENT = TargetType.SUNRISE;
    private static final String REGION = "Lake District";

    private ListAppender<ILoggingEvent> appender;
    private Logger helperLogger;

    @BeforeEach
    void attachAppender() {
        helperLogger = (Logger) LoggerFactory.getLogger(BriefingRatingStats.class);
        appender = new ListAppender<>();
        appender.start();
        helperLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        helperLogger.detachAppender(appender);
    }

    private static BriefingRatingStats.Entry entry(String location, Integer rating) {
        return new BriefingRatingStats.Entry(location, rating);
    }

    @Nested
    class WhenEntriesAreValid {

        @Test
        void countsHighMediumAndAverages() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("A", 5), entry("B", 4), entry("C", 3), entry("D", 2)),
                    REGION, DATE, EVENT);

            assertThat(stats.count()).isEqualTo(4);
            assertThat(stats.highRated()).isEqualTo(2L);
            assertThat(stats.mediumRated()).isEqualTo(1L);
            assertThat(stats.averageRating()).isEqualTo(3.5);
            assertThat(stats.isEmpty()).isFalse();
        }

        @Test
        void roundsAverageToOneDecimal() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("A", 5), entry("B", 4), entry("C", 4)),
                    REGION, DATE, EVENT);

            assertThat(stats.averageRating()).isEqualTo(4.3);
        }

        @Test
        void rating4IsHighBoundary() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("A", 4)), REGION, DATE, EVENT);

            assertThat(stats.highRated()).isEqualTo(1L);
            assertThat(stats.mediumRated()).isZero();
        }

        @Test
        void rating3IsMediumBoundary() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("A", 3)), REGION, DATE, EVENT);

            assertThat(stats.highRated()).isZero();
            assertThat(stats.mediumRated()).isEqualTo(1L);
        }
    }

    @Nested
    class WhenEntriesAreEmptyOrNull {

        @Test
        void emptyListReturnsEmptyStats() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(), REGION, DATE, EVENT);

            assertThat(stats.isEmpty()).isTrue();
            assertThat(stats.count()).isZero();
            assertThat(stats.averageRating()).isZero();
        }

        @Test
        void allNullRatingsReturnEmptyStatsSilently() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("A", null), entry("B", null)),
                    REGION, DATE, EVENT);

            assertThat(stats.isEmpty()).isTrue();
            assertThat(appender.list).isEmpty();
        }

        @Test
        void nullRatingsAreSkippedWithoutWarn() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("A", 4), entry("B", null), entry("C", 2)),
                    REGION, DATE, EVENT);

            assertThat(stats.count()).isEqualTo(2);
            assertThat(stats.averageRating()).isEqualTo(3.0);
            assertThat(appender.list).isEmpty();
        }
    }

    @Nested
    class WhenRatingsAreOutOfRange {

        @Test
        void ratingZeroIsSkippedAndLoggedAsWarn() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("Ullswater", 0), entry("Derwent", 4)),
                    REGION, DATE, EVENT);

            assertThat(stats.count()).isEqualTo(1);
            assertThat(stats.averageRating()).isEqualTo(4.0);

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            String msg = event.getFormattedMessage();
            assertThat(msg).contains("0");
            assertThat(msg).contains(REGION);
            assertThat(msg).contains(DATE.toString());
            assertThat(msg).contains(EVENT.toString());
            assertThat(msg).contains("Ullswater");
        }

        @Test
        void ratingSixIsSkippedAndLoggedAsWarn() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("Buttermere", 6)), REGION, DATE, EVENT);

            assertThat(stats.isEmpty()).isTrue();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("Buttermere");
        }

        @Test
        void negativeRatingIsSkippedAndLoggedAsWarn() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("Wastwater", -1)), REGION, DATE, EVENT);

            assertThat(stats.isEmpty()).isTrue();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        }
    }

    @Nested
    class WhenResolvingRegionDisplayVerdict {

        @Test
        void averageAt35ReturnsWorthIt() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("A", 4), entry("B", 3)), REGION, DATE, EVENT);
            assertThat(stats.averageRating()).isEqualTo(3.5);

            assertThat(BriefingRatingStats.resolveRegionDisplayVerdict(stats, null))
                    .isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        void averageJustBelow35ReturnsMaybe() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("A", 4), entry("B", 3), entry("C", 3)), REGION, DATE, EVENT);
            assertThat(stats.averageRating()).isEqualTo(3.3);

            assertThat(BriefingRatingStats.resolveRegionDisplayVerdict(stats, null))
                    .isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        void averageAt25ReturnsMaybe() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("A", 3), entry("B", 2)), REGION, DATE, EVENT);
            assertThat(stats.averageRating()).isEqualTo(2.5);

            assertThat(BriefingRatingStats.resolveRegionDisplayVerdict(stats, null))
                    .isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        void averageBelow25ReturnsStandDown() {
            BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                    List.of(entry("A", 2), entry("B", 2)), REGION, DATE, EVENT);

            assertThat(BriefingRatingStats.resolveRegionDisplayVerdict(stats, null))
                    .isEqualTo(DisplayVerdict.STAND_DOWN);
        }

        @Test
        void emptyStatsFallsBackToTriageGo() {
            assertThat(BriefingRatingStats.resolveRegionDisplayVerdict(
                    BriefingRatingStats.Stats.empty(), Verdict.GO))
                    .isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        void emptyStatsFallsBackToTriageMarginal() {
            assertThat(BriefingRatingStats.resolveRegionDisplayVerdict(
                    BriefingRatingStats.Stats.empty(), Verdict.MARGINAL))
                    .isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        void emptyStatsWithNullTriageReturnsAwaiting() {
            assertThat(BriefingRatingStats.resolveRegionDisplayVerdict(
                    BriefingRatingStats.Stats.empty(), null))
                    .isEqualTo(DisplayVerdict.AWAITING);
        }
    }
}
