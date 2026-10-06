package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
