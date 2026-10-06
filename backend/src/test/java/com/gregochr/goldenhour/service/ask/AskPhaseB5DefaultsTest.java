package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AskPhaseB5Defaults}: with nothing else defined the four B5 seams are the no-ops, and a real
 * implementation added later replaces exactly its own seam without a bean conflict.
 */
class AskPhaseB5DefaultsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AskPhaseB5Defaults.class);

    @Test
    @DisplayName("with no B5 implementation the four wired beans are the no-op defaults")
    void defaultsAreWired() {
        runner.run(context -> {
            assertThat(context.getBean(AskPreFilter.class)).isSameAs(AskPreFilter.NEVER_REFUSES);
            assertThat(context.getBean(AskIntentMatcher.class)).isSameAs(AskIntentMatcher.NEVER_MATCHES);
            assertThat(context.getBean(AskAnswerCache.class)).isSameAs(AskAnswerCache.DISABLED);
            assertThat(context.getBean(AskLog.class)).isSameAs(AskLog.DISCARD);
        });
    }

    @Test
    @DisplayName("a real pre-filter replaces only the pre-filter default; the other three stay no-ops")
    void aRealImplementationWins() {
        AskPreFilter real = question -> Optional.empty();

        runner.withBean("realPreFilter", AskPreFilter.class, () -> real).run(context -> {
            assertThat(context.getBean(AskPreFilter.class)).isSameAs(real);
            assertThat(context.getBean(AskIntentMatcher.class)).isSameAs(AskIntentMatcher.NEVER_MATCHES);
            assertThat(context.getBean(AskAnswerCache.class)).isSameAs(AskAnswerCache.DISABLED);
            assertThat(context.getBean(AskLog.class)).isSameAs(AskLog.DISCARD);
        });
    }
}
