package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared rule for picking the one coastal location a multi-location tide claim is drawn for.
 *
 * <p>The selection itself is exercised through both callers — {@code TideRunBuilderTest} for the
 * spring-run pill and {@code WindowTideRollupBuilderTest} for the Plan tab's tide row. What is
 * pinned <em>here</em> is the part neither caller can see: the warn-once state is <b>per instance</b>,
 * so a misconfigured anchor is reported once by each surface rather than once in total. That is the
 * whole reason this is a constructed object and not a Spring bean, and a later tidy-up that makes it
 * `@Component` would silence half the diagnostics with every other test still green.
 */
class TideRepresentativeSelectorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 27);

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(TideRepresentativeSelector.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("an unresolvable anchor warns once, however many times it is asked")
    void anUnresolvableAnchorWarnsOnce() {
        // Both callers recompute on every serve, so an unguarded warning writes a line per
        // request — and a message that noisy stops being read, which is how a silent fallback
        // becomes silent again.
        TideRepresentativeSelector selector = new TideRepresentativeSelector("Nowhere Point");

        selector.select(List.of(seaham()), extremes(), List.of(DAY), drawable());
        selector.select(List.of(seaham()), extremes(), List.of(DAY), drawable());
        selector.select(List.of(seaham()), extremes(), List.of(DAY), drawable());

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("Nowhere Point").contains("Location Management");
    }

    @Test
    @DisplayName("two callers each get the warning — the state is per instance, not global")
    void eachCallerWarnsIndependently() {
        // Sharing one instance between the tide run and the window rollup would warn for
        // whichever ran first and stay silent for the other, so a surface could draw itself at a
        // cove 90 miles from the reader with nothing anywhere saying why.
        new TideRepresentativeSelector("Nowhere Point")
                .select(List.of(seaham()), extremes(), List.of(DAY), drawable());
        new TideRepresentativeSelector("Nowhere Point")
                .select(List.of(seaham()), extremes(), List.of(DAY), drawable());

        assertThat(warnings()).hasSize(2);
    }

    @Test
    @DisplayName("no configured anchor is silence, not a warning")
    void anUnconfiguredAnchorIsNotAProblem() {
        // Biggest-range is the documented default, not a degraded state — warning about it would
        // train the reader to ignore the messages that matter.
        assertThat(new TideRepresentativeSelector("")
                .select(List.of(seaham()), extremes(), List.of(DAY), drawable()).getName())
                .isEqualTo("Seaham");
        assertThat(new TideRepresentativeSelector(null)
                .select(List.of(seaham()), extremes(), List.of(DAY), drawable()))
                .isNotNull();

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("the caller's own drawability probe decides, not a rule owned here")
    void theProbeDecidesWhatIsDrawable() {
        // Two surfaces, two definitions of "drawable". A shared private rule would let a change to
        // one silently re-select the other's representative.
        TideRepresentativeSelector selector = new TideRepresentativeSelector("");

        assertThat(selector.select(List.of(seaham()), extremes(), List.of(DAY),
                (extremes, date) -> OptionalDouble.empty())).isNull();
        assertThat(selector.select(List.of(seaham()), extremes(), List.of(DAY), drawable())
                .getName()).isEqualTo("Seaham");
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** Every location is drawable with a 4.6 m range, so selection turns only on the anchor. */
    private static TideRepresentativeSelector.DayRange drawable() {
        return (extremes, date) -> OptionalDouble.of(4.6);
    }

    private static Map<Long, List<TideExtremeEntity>> extremes() {
        return Map.of(2L, List.of());
    }

    private static LocationEntity seaham() {
        return LocationEntity.builder().id(2L).name("Seaham").lat(54.84).lon(-1.33).build();
    }
}
