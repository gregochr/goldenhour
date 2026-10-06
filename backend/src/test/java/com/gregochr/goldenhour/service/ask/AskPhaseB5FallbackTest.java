package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.repository.AskLogRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.HotTopicSimulationService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The B5 seams' no-op stand-ins are {@code @Fallback} component classes: with nothing else defined
 * each seam resolves to its no-op, and a plain user-supplied bean (what B5 will add, with no
 * qualifier and no {@code @Primary}) wins over it without a conflict, whatever the registration order.
 */
class AskPhaseB5FallbackTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(
            NoOpAskPreFilter.class, NoOpAskIntentMatcher.class, NoOpAskAnswerCache.class, NoOpAskLog.class);

    @Test
    @DisplayName("with no B5 implementation each seam is its no-op class")
    void defaultsAreWired() {
        runner.run(context -> {
            assertThat(context.getBean(AskPreFilter.class)).isInstanceOf(NoOpAskPreFilter.class);
            assertThat(context.getBean(AskIntentMatcher.class)).isInstanceOf(NoOpAskIntentMatcher.class);
            assertThat(context.getBean(AskAnswerCache.class)).isInstanceOf(NoOpAskAnswerCache.class);
            assertThat(context.getBean(AskLog.class)).isInstanceOf(NoOpAskLog.class);
        });
    }

    @Test
    @DisplayName("a user-supplied pre-filter wins over the no-op, and only that seam changes")
    void aRealPreFilterWins() {
        AskPreFilter real = question -> Optional.empty();

        runner.withBean("realPreFilter", AskPreFilter.class, () -> real).run(context -> {
            assertThat(context.getBean(AskPreFilter.class)).isSameAs(real);
            assertThat(context.getBean(AskIntentMatcher.class)).isInstanceOf(NoOpAskIntentMatcher.class);
            assertThat(context.getBean(AskAnswerCache.class)).isInstanceOf(NoOpAskAnswerCache.class);
            assertThat(context.getBean(AskLog.class)).isInstanceOf(NoOpAskLog.class);
        });
    }

    @Test
    @DisplayName("a user-supplied intent matcher, cache and log each win over their no-op")
    void realImplementationsWin() {
        AskIntentMatcher matcher = new RealMatcher();
        AskAnswerCache cache = new RealCache();
        AskLog log = entry -> { };

        runner.withBean("realMatcher", AskIntentMatcher.class, () -> matcher)
                .withBean("realCache", AskAnswerCache.class, () -> cache)
                .withBean("realLog", AskLog.class, () -> log)
                .run(context -> {
                    assertThat(context.getBean(AskIntentMatcher.class)).isSameAs(matcher);
                    assertThat(context.getBean(AskAnswerCache.class)).isSameAs(cache);
                    assertThat(context.getBean(AskLog.class)).isSameAs(log);
                    assertThat(context.getBean(AskPreFilter.class)).isInstanceOf(NoOpAskPreFilter.class);
                });
    }

    @Test
    @DisplayName("the winner is also the one injected into a consumer, as AskService's constructor takes them")
    void injectionPicksTheRealOne() {
        AskPreFilter real = question -> Optional.empty();

        runner.withBean("realPreFilter", AskPreFilter.class, () -> real)
                .withBean(Consumer.class)
                .run(context -> assertThat(context.getBean(Consumer.class).preFilter).isSameAs(real));
    }

    @Test
    @DisplayName("B5's own four components, registered beside the no-ops with no qualifier, are the beans "
            + "resolved and injected: nothing was deleted and nothing conflicts")
    void b5ComponentsWinOverTheNoOps() {
        runner.withUserConfiguration(PhraseAskPreFilter.class, KeywordAskIntentMatcher.class,
                        CaffeineAskAnswerCache.class, DatabaseAskLog.class, Everything.class)
                .withBean(AskReadyService.class, () -> mock(AskReadyService.class))
                .withBean(AskProperties.class, AskProperties::new)
                .withBean(RegionRepository.class, () -> mock(RegionRepository.class))
                .withBean(HotTopicSimulationService.class, () -> mock(HotTopicSimulationService.class))
                .withBean(AuroraStateCache.class, () -> mock(AuroraStateCache.class))
                .withBean(AskLogRepository.class, () -> mock(AskLogRepository.class))
                .withBean(Clock.class, Clock::systemUTC)
                .run(context -> {
                    assertThat(context.getBean(AskPreFilter.class)).isInstanceOf(PhraseAskPreFilter.class);
                    assertThat(context.getBean(AskIntentMatcher.class))
                            .isInstanceOf(KeywordAskIntentMatcher.class);
                    assertThat(context.getBean(AskAnswerCache.class))
                            .isInstanceOf(CaffeineAskAnswerCache.class);
                    assertThat(context.getBean(AskLog.class)).isInstanceOf(DatabaseAskLog.class);
                    Everything consumer = context.getBean(Everything.class);
                    assertThat(consumer.preFilter).isInstanceOf(PhraseAskPreFilter.class);
                    assertThat(consumer.matcher).isInstanceOf(KeywordAskIntentMatcher.class);
                    assertThat(consumer.cache).isInstanceOf(CaffeineAskAnswerCache.class);
                    assertThat(consumer.log).isInstanceOf(DatabaseAskLog.class);
                });
    }

    /** Takes all four seams by constructor, as {@code AskService} does. */
    static class Everything {
        private final AskPreFilter preFilter;
        private final AskIntentMatcher matcher;
        private final AskAnswerCache cache;
        private final AskLog log;

        Everything(AskPreFilter preFilter, AskIntentMatcher matcher, AskAnswerCache cache, AskLog log) {
            this.preFilter = preFilter;
            this.matcher = matcher;
            this.cache = cache;
            this.log = log;
        }
    }

    /** Takes one seam by constructor, as {@code AskService} does. */
    static class Consumer {
        private final AskPreFilter preFilter;

        Consumer(AskPreFilter preFilter) {
            this.preFilter = preFilter;
        }
    }

    /** A stand-in for B5's matcher. */
    private static final class RealMatcher implements AskIntentMatcher {
        @Override
        public Optional<AskReadyResponse.Question> match(AskQuestion question, AskSnapshot snapshot,
                String scopeKey, Collection<String> scopeNames) {
            return Optional.empty();
        }
    }

    /** A stand-in for B5's cache. */
    private static final class RealCache implements AskAnswerCache {
        @Override
        public Optional<AskAnswer> lookup(AskQuestion question, AskSnapshot snapshot, AskUserContext user) {
            return Optional.empty();
        }

        @Override
        public void store(AskQuestion question, AskSnapshot snapshot, AskUserContext user,
                AskOutcome outcome) {
            // A real cache would keep it.
        }
    }
}
