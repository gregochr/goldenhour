package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.UserRole;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Bound to {@code photocast.ask} (plan §2.9).
 *
 * <p>Every bound is enforced by the setter, so an out-of-range value fails startup (the binder
 * wraps the {@link IllegalArgumentException}) exactly as {@code BatchCachePrimerProperties} does.
 * Settings whose consumers arrive in later phases (the allowance limits, the cache, the log
 * retention, the Ready cap) are declared and bounded here so the whole contract lives in one place
 * and a bad value is caught the day it is written, not the day the consumer ships.
 *
 * <p>{@link #getModel} is restricted to {@link EvaluationModel#HAIKU} and
 * {@link EvaluationModel#SONNET}. There is deliberately no {@code model_selection} row and no
 * Models-screen entry for Ask: Sonnet 5.5 cannot disable thinking, which breaks the token and time
 * budgets below, so it must not be selectable here.
 */
@Component
@ConfigurationProperties(prefix = "photocast.ask")
@Getter
public class AskProperties implements InitializingBean {

    /** Fewest model turns a conversation may be allowed. */
    public static final int MIN_TURNS = 1;
    /** Most model turns a conversation may be allowed. */
    public static final int MAX_TURNS = 6;
    /** Smallest {@code max_tokens} accepted. */
    public static final int MIN_MAX_TOKENS = 200;
    /** Largest {@code max_tokens} accepted. */
    public static final int MAX_MAX_TOKENS = 2_000;
    /** Longest per-call timeout, in seconds. */
    public static final int MAX_CALL_TIMEOUT_SECONDS = 60;
    /** Longest conversation deadline, in seconds. */
    public static final int MAX_DEADLINE_SECONDS = 120;
    /** Highest per-minute rate accepted. */
    public static final int MAX_RATE_PER_MINUTE = 600;
    /** Highest daily allowance accepted for any role. */
    public static final int MAX_DAILY_LIMIT = 1_000;
    /** Highest engine-ceiling multiplier accepted. */
    public static final int MAX_CEILING_MULTIPLIER = 20;
    /** Highest daily spend cap accepted, in US dollars. */
    public static final double MAX_SPEND_CAP_USD = 1_000.0;
    /** Most precompute cycles a day accepted. */
    public static final int MAX_READY_CYCLES_PER_DAY = 48;
    /** Most cache entries accepted. */
    public static final int MAX_CACHE_ENTRIES = 100_000;
    /** Longest cache lifetime accepted, in minutes. */
    public static final int MAX_CACHE_TTL_MINUTES = 1_440;
    /** Longest log retention accepted, in days. */
    public static final int MAX_LOG_RETENTION_DAYS = 3_650;

    private static final long MICRO_DOLLARS_PER_DOLLAR = 1_000_000L;

    /** Whether Ask is on at all. Off by default. */
    @Setter
    private boolean enabled;

    /** Whether the stub engine answers instead of Claude (local verification, no spend). */
    @Setter
    private boolean stub;

    private EvaluationModel model = EvaluationModel.HAIKU;
    private int maxTurns = 4;
    private int maxTokens = 600;
    private int callTimeoutSeconds = 20;
    private int deadlineSeconds = 30;
    private int ratePerMinute = 5;
    private int limitLite = 3;
    private int limitPro = 30;
    private int engineCeilingMultiplier = 3;
    private double dailySpendCapUsd = 0.50;

    /** Ready-answer precompute settings. */
    private final Ready ready = new Ready();

    /** Typed-answer cache settings. */
    private final Cache cache = new Cache();

    /** Question-log settings. */
    private final Log log = new Log();

    /**
     * Sets the model that answers typed questions.
     *
     * @param model HAIKU or SONNET; nothing else is accepted
     * @throws IllegalArgumentException for any other model, so a bad value fails startup
     */
    public void setModel(EvaluationModel model) {
        if (model != EvaluationModel.HAIKU && model != EvaluationModel.SONNET) {
            throw new IllegalArgumentException(
                    "photocast.ask.model must be HAIKU or SONNET but was " + model);
        }
        this.model = model;
    }

    /**
     * Sets the most model turns in one conversation.
     *
     * @param maxTurns 1 to 6
     * @throws IllegalArgumentException if out of range
     */
    public void setMaxTurns(int maxTurns) {
        this.maxTurns = within("max-turns", maxTurns, MIN_TURNS, MAX_TURNS);
    }

    /**
     * Sets the {@code max_tokens} of each model turn.
     *
     * @param maxTokens 200 to 2000
     * @throws IllegalArgumentException if out of range
     */
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = within("max-tokens", maxTokens, MIN_MAX_TOKENS, MAX_MAX_TOKENS);
    }

    /**
     * Sets the per-call timeout.
     *
     * @param callTimeoutSeconds 1 to 60
     * @throws IllegalArgumentException if out of range
     */
    public void setCallTimeoutSeconds(int callTimeoutSeconds) {
        this.callTimeoutSeconds = within("call-timeout-seconds", callTimeoutSeconds, 1,
                MAX_CALL_TIMEOUT_SECONDS);
    }

    /**
     * Sets the whole-conversation deadline.
     *
     * @param deadlineSeconds 1 to 120
     * @throws IllegalArgumentException if out of range
     */
    public void setDeadlineSeconds(int deadlineSeconds) {
        this.deadlineSeconds = within("deadline-seconds", deadlineSeconds, 1, MAX_DEADLINE_SECONDS);
    }

    /**
     * Sets the typed-question rate limit.
     *
     * @param ratePerMinute 1 to 600
     * @throws IllegalArgumentException if out of range
     */
    public void setRatePerMinute(int ratePerMinute) {
        this.ratePerMinute = within("rate-per-minute", ratePerMinute, 1, MAX_RATE_PER_MINUTE);
    }

    /**
     * Sets the daily allowance of a LITE user.
     *
     * @param limitLite 0 to 1000
     * @throws IllegalArgumentException if out of range
     */
    public void setLimitLite(int limitLite) {
        this.limitLite = within("limit-lite", limitLite, 0, MAX_DAILY_LIMIT);
    }

    /**
     * Sets the daily allowance of a PRO or ADMIN user.
     *
     * @param limitPro 0 to 1000
     * @throws IllegalArgumentException if out of range
     */
    public void setLimitPro(int limitPro) {
        this.limitPro = within("limit-pro", limitPro, 0, MAX_DAILY_LIMIT);
    }

    /**
     * Sets the multiplier of the never-refunded daily engine-call ceiling.
     *
     * @param engineCeilingMultiplier 1 to 20
     * @throws IllegalArgumentException if out of range
     */
    public void setEngineCeilingMultiplier(int engineCeilingMultiplier) {
        this.engineCeilingMultiplier = within("engine-ceiling-multiplier", engineCeilingMultiplier, 1,
                MAX_CEILING_MULTIPLIER);
    }

    /**
     * Sets the daily typed-question spend cap.
     *
     * @param dailySpendCapUsd above 0 and at most 1000 US dollars
     * @throws IllegalArgumentException if out of range or not a number
     */
    public void setDailySpendCapUsd(double dailySpendCapUsd) {
        if (Double.isNaN(dailySpendCapUsd) || dailySpendCapUsd <= 0
                || dailySpendCapUsd > MAX_SPEND_CAP_USD) {
            throw new IllegalArgumentException("photocast.ask.daily-spend-cap-usd must be above 0 and at most "
                    + MAX_SPEND_CAP_USD + " but was " + dailySpendCapUsd);
        }
        this.dailySpendCapUsd = dailySpendCapUsd;
    }

    /**
     * The daily typed spend cap in micro-dollars, the unit {@code api_call_log} records.
     *
     * @return the cap in micro-dollars
     */
    public long dailySpendCapMicroDollars() {
        return BigDecimal.valueOf(dailySpendCapUsd)
                .multiply(BigDecimal.valueOf(MICRO_DOLLARS_PER_DOLLAR))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * A role's daily allowance of typed questions (plan §2.5 step 8, §6 Q6): LITE
     * {@code limit-lite}, PRO and ADMIN {@code limit-pro} (D-7: ADMIN has the PRO allowance; the
     * spend cap is the real ceiling). A null role gets the smallest allowance, so an unresolved
     * role can only ever ask for less.
     *
     * @param role the asker's role, or null
     * @return the number of typed questions that role may ask a UK day
     */
    public int limitFor(UserRole role) {
        if (role == UserRole.PRO_USER || role == UserRole.ADMIN) {
            return limitPro;
        }
        return limitLite;
    }

    /**
     * The never-refunded daily ceiling on engine calls for a role: {@code engine-ceiling-multiplier}
     * times its allowance (plan §1 #20). Refunds make an unanswerable question free to the asker, so
     * this is what bounds the Claude calls a user can still cause in a day.
     *
     * @param role the asker's role, or null
     * @return the most engine calls that role may cause a UK day
     */
    public int engineCeilingFor(UserRole role) {
        return limitFor(role) * engineCeilingMultiplier;
    }

    /**
     * Cross-field check, run once the properties are bound: a per-call timeout longer than the
     * conversation deadline could never be reached, which is a configuration mistake, not a choice.
     *
     * @throws IllegalArgumentException if the call timeout exceeds the deadline, so it fails startup
     */
    @Override
    public void afterPropertiesSet() {
        if (callTimeoutSeconds > deadlineSeconds) {
            throw new IllegalArgumentException("photocast.ask.call-timeout-seconds (" + callTimeoutSeconds
                    + ") must not exceed photocast.ask.deadline-seconds (" + deadlineSeconds + ")");
        }
    }

    private static int within(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("photocast.ask." + key + " must be " + min + ".." + max
                    + " but was " + value);
        }
        return value;
    }

    /** {@code photocast.ask.ready.*}. */
    @Getter
    public static class Ready {

        private int maxCyclesPerDay = 6;

        /**
         * Sets the most precompute cycles a UK day may run.
         *
         * @param maxCyclesPerDay 1 to 48
         * @throws IllegalArgumentException if out of range
         */
        public void setMaxCyclesPerDay(int maxCyclesPerDay) {
            this.maxCyclesPerDay = within("ready.max-cycles-per-day", maxCyclesPerDay, 1,
                    MAX_READY_CYCLES_PER_DAY);
        }
    }

    /** {@code photocast.ask.cache.*}. */
    @Getter
    public static class Cache {

        private int maxEntries = 2_000;
        private int ttlMinutes = 30;

        /**
         * Sets the typed-answer cache size.
         *
         * @param maxEntries 1 to 100000
         * @throws IllegalArgumentException if out of range
         */
        public void setMaxEntries(int maxEntries) {
            this.maxEntries = within("cache.max-entries", maxEntries, 1, MAX_CACHE_ENTRIES);
        }

        /**
         * Sets the typed-answer cache lifetime.
         *
         * @param ttlMinutes 1 to 1440
         * @throws IllegalArgumentException if out of range
         */
        public void setTtlMinutes(int ttlMinutes) {
            this.ttlMinutes = within("cache.ttl-minutes", ttlMinutes, 1, MAX_CACHE_TTL_MINUTES);
        }
    }

    /** {@code photocast.ask.log.*}. */
    @Getter
    public static class Log {

        private int retentionDays = 90;

        /**
         * Sets how long the question log is kept.
         *
         * @param retentionDays 1 to 3650
         * @throws IllegalArgumentException if out of range
         */
        public void setRetentionDays(int retentionDays) {
            this.retentionDays = within("log.retention-days", retentionDays, 1,
                    MAX_LOG_RETENTION_DAYS);
        }
    }
}
