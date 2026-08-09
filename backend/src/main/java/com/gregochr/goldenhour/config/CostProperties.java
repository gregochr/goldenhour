package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code cost} section of {@code application.yml}.
 *
 * <p>Token-based pricing: rates are in USD per million tokens, matching Anthropic's published
 * pricing. Costs are calculated in micro-dollars (1 dollar = 1,000,000 micro-dollars) for
 * precision.
 */
@Component
@ConfigurationProperties(prefix = "cost")
@Getter
@Setter
public class CostProperties {

    // --- Token-based pricing (USD per million tokens) ---

    /** Haiku 4.5 input rate (USD per million tokens). */
    private double haikuInputUsdPerMtok = 1.00;

    /** Haiku 4.5 output rate (USD per million tokens). */
    private double haikuOutputUsdPerMtok = 5.00;

    /** Haiku 4.5 cache write rate (USD per million tokens). */
    private double haikuCacheWriteUsdPerMtok = 1.25;

    /** Haiku 4.5 cache read rate (USD per million tokens). */
    private double haikuCacheReadUsdPerMtok = 0.10;

    /** Sonnet 4.6 input rate (USD per million tokens). */
    private double sonnetInputUsdPerMtok = 3.00;

    /** Sonnet 4.6 output rate (USD per million tokens). */
    private double sonnetOutputUsdPerMtok = 15.00;

    /** Sonnet 4.6 cache write rate (USD per million tokens). */
    private double sonnetCacheWriteUsdPerMtok = 3.75;

    /** Sonnet 4.6 cache read rate (USD per million tokens). */
    private double sonnetCacheReadUsdPerMtok = 0.30;

    /** Opus 4.6 input rate (USD per million tokens). */
    private double opusInputUsdPerMtok = 5.00;

    /** Opus 4.6 output rate (USD per million tokens). */
    private double opusOutputUsdPerMtok = 25.00;

    /** Opus 4.6 cache write rate (USD per million tokens). */
    private double opusCacheWriteUsdPerMtok = 6.25;

    /** Opus 4.6 cache read rate (USD per million tokens). */
    private double opusCacheReadUsdPerMtok = 0.50;

    // --- Non-Anthropic flat costs (micro-dollars) ---

    /**
     * WorldTides fallback cost in micro-dollars, used only when the response reports no credit
     * count (an error, or a cached response predating the mapping of {@code callCount}).
     *
     * <p>Kept as an estimate of a typical call rather than raised to match the current horizon:
     * its job is to avoid logging a free call, not to be accurate. The accurate path is
     * {@link #worldTidesMicroDollarsPerCredit} times the provider's own reported credits.
     */
    private long worldTidesMicroDollars = 3000;

    /**
     * WorldTides cost per credit in micro-dollars.
     *
     * <p>$4.99/month for 20,000 credits on the cheapest paid tier is $0.00024950 per credit.
     * Credits scale with the window requested — "a seven-day high/low tide prediction costs one
     * credit for a location" — so a 97-day fetch is 14 credits, not one.
     */
    private long worldTidesMicroDollarsPerCredit = 250;

    /** Open-Meteo API call cost in micro-dollars (free). */
    private long openMeteoMicroDollars = 0;
}
