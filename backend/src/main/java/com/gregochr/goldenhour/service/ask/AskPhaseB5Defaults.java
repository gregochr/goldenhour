package com.gregochr.goldenhour.service.ask;

import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the no-op stand-ins for the steps B5 owns (pre-filter, Ready intent match, typed cache, log),
 * so the typed endpoint's order is complete in B4. Each is a {@link Fallback} bean: B5 adds a real
 * {@code @Component} implementing the interface and injection picks it over the default, with nothing
 * to delete here and no bean conflict. {@code @Fallback} rather than {@code @ConditionalOnMissingBean}
 * because the latter is only reliable in auto-configuration: on an ordinary component-scanned
 * configuration it depends on the order classes happen to be scanned in.
 */
@Configuration
public class AskPhaseB5Defaults {

    /**
     * The pre-filter that refuses nothing.
     *
     * @return the default
     */
    @Bean
    @Fallback
    public AskPreFilter askPreFilter() {
        return AskPreFilter.NEVER_REFUSES;
    }

    /**
     * The intent matcher that matches nothing.
     *
     * @return the default
     */
    @Bean
    @Fallback
    public AskIntentMatcher askIntentMatcher() {
        return AskIntentMatcher.NEVER_MATCHES;
    }

    /**
     * The cache that always misses and stores nothing.
     *
     * @return the default
     */
    @Bean
    @Fallback
    public AskAnswerCache askAnswerCache() {
        return AskAnswerCache.DISABLED;
    }

    /**
     * The log that records nothing.
     *
     * @return the default
     */
    @Bean
    @Fallback
    public AskLog askLog() {
        return AskLog.DISCARD;
    }
}
