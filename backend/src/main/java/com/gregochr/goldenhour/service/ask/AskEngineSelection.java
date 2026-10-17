package com.gregochr.goldenhour.service.ask;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Chooses which {@link AskEngine} is the application's one engine: the stub when
 * {@code photocast.ask.stub} is true, Claude otherwise.
 *
 * <p><b>Exactly one engine bean, by construction.</b> Both conditions read the one property through
 * the one conversion ({@link org.springframework.core.env.Environment#getProperty(String, Class,
 * Object)} to a {@code Boolean}, the conversion the {@link AskProperties} binder itself uses), and
 * the Claude condition is the stub condition's negation. Two {@code @ConditionalOnProperty}
 * annotations ({@code havingValue = "true"} against {@code "false"}) could not promise that: a
 * value such as {@code stub=yes} binds to true yet matches neither, which would leave <em>no</em>
 * engine. Here a value that reads as true selects the stub and every other value the Claude engine,
 * and a value that is not a boolean at all fails startup, as it does for the binder.
 *
 * <p>The default (the property absent) is the Claude engine: the stub is something a developer
 * switches on, never something production could be left on by omission. And it cannot be switched
 * on <em>into</em> production either: {@code stub=true} under the {@code prod} profile fails startup
 * with a message saying why, rather than either serving templates to readers or quietly falling back
 * to the engine that bills the real key.
 */
public final class AskEngineSelection {

    /** The property both conditions read. */
    public static final String STUB_PROPERTY = "photocast.ask.stub";

    /** The profile under which the stub is refused. */
    private static final String PRODUCTION_PROFILE = "prod";

    private AskEngineSelection() {
    }

    /**
     * Whether the stub engine is selected.
     *
     * @param context the condition context
     * @return true when {@code photocast.ask.stub} reads as true
     */
    static boolean stubSelected(ConditionContext context) {
        boolean stub = Boolean.TRUE.equals(
                context.getEnvironment().getProperty(STUB_PROPERTY, Boolean.class, Boolean.FALSE));
        if (stub && context.getEnvironment().acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            throw new IllegalStateException(STUB_PROPERTY + "=true is refused under the " + PRODUCTION_PROFILE
                    + " profile: every reader's question would be answered from a template");
        }
        return stub;
    }

    /** Matches when {@code photocast.ask.stub} is true: {@link StubAskEngine} is the engine. */
    public static final class StubSelected implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return stubSelected(context);
        }
    }

    /** Matches unless {@code photocast.ask.stub} is true: {@link ClaudeAskEngine} is the engine. */
    public static final class ClaudeSelected implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !stubSelected(context);
        }
    }
}
