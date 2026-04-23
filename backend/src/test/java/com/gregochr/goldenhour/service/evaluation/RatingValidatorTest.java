package com.gregochr.goldenhour.service.evaluation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RatingValidatorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 23);
    private static final TargetType EVENT = TargetType.SUNSET;
    private static final String REGION = "Yorkshire Dales";
    private static final String LOCATION = "Almscliffe Crag";
    private static final String MODEL = "claude-sonnet-4-6";

    private ListAppender<ILoggingEvent> appender;
    private Logger helperLogger;

    @BeforeEach
    void attachAppender() {
        helperLogger = (Logger) LoggerFactory.getLogger(RatingValidator.class);
        appender = new ListAppender<>();
        appender.start();
        helperLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        helperLogger.detachAppender(appender);
    }

    @Nested
    class ValidateRating {

        @Test
        void nullRatingReturnsNullSilently() {
            Integer result = RatingValidator.validateRating(null, REGION, DATE, EVENT, LOCATION, MODEL);

            assertThat(result).isNull();
            assertThat(appender.list).isEmpty();
        }

        @Test
        void inRangeRatingReturnsUnchanged() {
            for (int rating = 1; rating <= 5; rating++) {
                Integer result = RatingValidator.validateRating(rating, REGION, DATE, EVENT, LOCATION, MODEL);
                assertThat(result).isEqualTo(rating);
            }
            assertThat(appender.list).isEmpty();
        }

        @Test
        void ratingOneIsLowerBoundary() {
            Integer result = RatingValidator.validateRating(1, REGION, DATE, EVENT, LOCATION, MODEL);

            assertThat(result).isEqualTo(1);
            assertThat(appender.list).isEmpty();
        }

        @Test
        void ratingFiveIsUpperBoundary() {
            Integer result = RatingValidator.validateRating(5, REGION, DATE, EVENT, LOCATION, MODEL);

            assertThat(result).isEqualTo(5);
            assertThat(appender.list).isEmpty();
        }

        @Test
        void zeroRatingIsRejectedAndLogged() {
            Integer result = RatingValidator.validateRating(0, REGION, DATE, EVENT, LOCATION, MODEL);

            assertThat(result).isNull();
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("[RATING GUARDRAIL]");
            assertThat(event.getFormattedMessage()).contains("rating=0");
        }

        @Test
        void sixRatingIsRejectedAndLogged() {
            Integer result = RatingValidator.validateRating(6, REGION, DATE, EVENT, LOCATION, MODEL);

            assertThat(result).isNull();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        }

        @Test
        void negativeRatingIsRejectedAndLogged() {
            Integer result = RatingValidator.validateRating(-2, REGION, DATE, EVENT, LOCATION, MODEL);

            assertThat(result).isNull();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("rating=-2");
        }

        @Test
        void extremelyLargeRatingIsRejectedAndLogged() {
            Integer result = RatingValidator.validateRating(491, REGION, DATE, EVENT, LOCATION, MODEL);

            assertThat(result).isNull();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("rating=491");
        }

        @Test
        void warnMessageIncludesAllContextFields() {
            RatingValidator.validateRating(13, REGION, DATE, EVENT, LOCATION, MODEL);

            assertThat(appender.list).hasSize(1);
            String msg = appender.list.get(0).getFormattedMessage();
            assertThat(msg).contains("region=" + REGION);
            assertThat(msg).contains("date=" + DATE);
            assertThat(msg).contains("event=" + EVENT);
            assertThat(msg).contains("location=" + LOCATION);
            assertThat(msg).contains("model=" + MODEL);
            assertThat(msg).contains("rating=13");
        }

        @Test
        void nullContextFieldsAreTolerated() {
            Integer result = RatingValidator.validateRating(99, null, null, null, null, null);

            assertThat(result).isNull();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("rating=99");
        }
    }

    @Nested
    class ValidateScore {

        @Test
        void nullScoreReturnsNullSilently() {
            Integer result = RatingValidator.validateScore(null, 0, 100, "fiery_sky", LOCATION, MODEL);

            assertThat(result).isNull();
            assertThat(appender.list).isEmpty();
        }

        @Test
        void inRangeScoreReturnsUnchanged() {
            Integer result = RatingValidator.validateScore(42, 0, 100, "fiery_sky", LOCATION, MODEL);

            assertThat(result).isEqualTo(42);
            assertThat(appender.list).isEmpty();
        }

        @Test
        void lowerBoundaryIsAccepted() {
            assertThat(RatingValidator.validateScore(0, 0, 100, "fiery_sky", LOCATION, MODEL))
                    .isEqualTo(0);
        }

        @Test
        void upperBoundaryIsAccepted() {
            assertThat(RatingValidator.validateScore(100, 0, 100, "fiery_sky", LOCATION, MODEL))
                    .isEqualTo(100);
        }

        @Test
        void outOfRangeScoreIsRejectedAndLogged() {
            Integer result = RatingValidator.validateScore(250, 0, 100, "golden_hour", LOCATION, MODEL);

            assertThat(result).isNull();
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            String msg = event.getFormattedMessage();
            assertThat(msg).contains("[RATING GUARDRAIL]");
            assertThat(msg).contains("golden_hour");
            assertThat(msg).contains("value=250");
            assertThat(msg).contains("range=[0,100]");
        }

        @Test
        void negativeScoreIsRejectedAndLogged() {
            Integer result = RatingValidator.validateScore(-5, 0, 10, "inversion_score", LOCATION, MODEL);

            assertThat(result).isNull();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("inversion_score");
        }
    }
}
