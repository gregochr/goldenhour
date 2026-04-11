package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideSnapshot;

/**
 * Extends {@link PromptBuilder} with coastal-specific guidance for tide
 * and storm surge conditions.
 *
 * <p>Used for locations where {@link AtmosphericData#tide()} is non-null.
 * Inland locations use the base {@link PromptBuilder} directly.
 *
 * <p>The system prompt appends a COASTAL TIDE GUIDANCE section after the
 * base prompt. The {@code buildUserMessage} overrides append tide and surge
 * data after the base sky/aerosol/mist content.
 *
 * <p>Prompt caching: all coastal locations share the same system prompt
 * prefix (base + coastal section), maximising cache hit rate.
 */
public class CoastalPromptBuilder extends PromptBuilder {

    /**
     * Coastal tide and surge guidance appended to the base system prompt.
     *
     * <p>Sky scoring rules are unchanged — a good sky is required regardless
     * of tide conditions. Tide is a supporting factor, not a substitute
     * for a photogenic sky.
     */
    static final String COASTAL_SYSTEM_PROMPT_SUFFIX =
            "COASTAL TIDE GUIDANCE:\n"
            + "For coastal locations, tide data is provided. Tide is a supporting "
            + "factor — it can boost a good sky score but cannot compensate for a "
            + "poor sky. Apply these rules:\n"
            + "- Sky score first: establish the rating from sky conditions alone "
            + "before considering tide.\n"
            + "- Tide boost: a well-timed high tide (aligned within the golden/blue "
            + "hour window) may boost rating by +1 IF the sky score is already \u22653. "
            + "A 2\u2605 sky + perfect king tide = 2\u2605. A 3\u2605 sky + aligned king tide = 4\u2605.\n"
            + "- King tide / spring tide: lunar classification indicates a "
            + "photogenically significant tide — larger range, more dramatic "
            + "foreground. Factor this positively when tide is aligned.\n"
            + "- Storm surge: when a STORM SURGE FORECAST block is present, the "
            + "tide will be higher than predicted — more dramatic foreground, but "
            + "note safety implications at exposed locations.\n"
            + "- Tide not aligned: mention briefly but do not penalise the sky "
            + "score. The sky is the primary subject.\n"
            + "- Missing tide data: score on sky alone, do not penalise.\n\n";

    /**
     * Returns the base system prompt with the coastal tide guidance appended.
     *
     * @return the full system prompt for coastal location evaluations
     */
    @Override
    public String getSystemPrompt() {
        return super.getSystemPrompt() + COASTAL_SYSTEM_PROMPT_SUFFIX;
    }

    /**
     * Builds the user message including tide data after the base sky content.
     *
     * @param data the atmospheric forecast data (must have non-null tide())
     * @return formatted user message with sky + tide sections
     */
    @Override
    public String buildUserMessage(AtmosphericData data) {
        String base = super.buildUserMessage(data);

        TideSnapshot tide = data.tide();
        if (tide == null || tide.tideState() == null) {
            return base;
        }

        StringBuilder tideBlock = new StringBuilder();
        appendTideBlock(tideBlock, tide);
        return PromptUtils.insertBeforeSuffix(base, getPromptSuffix(), tideBlock.toString());
    }

    /**
     * Builds the user message including tide and storm surge data.
     *
     * @param data               the atmospheric forecast data
     * @param surge              storm surge breakdown, or null
     * @param adjustedRangeM     adjusted tidal range including surge, or null
     * @param astronomicalRangeM astronomical tidal range before surge, or null
     * @return formatted user message with sky + tide + surge sections
     */
    @Override
    public String buildUserMessage(AtmosphericData data,
                                   StormSurgeBreakdown surge,
                                   Double adjustedRangeM,
                                   Double astronomicalRangeM) {
        String withTide = buildUserMessage(data);
        String surgeBlock = SurgeBlockFormatter.format(surge, adjustedRangeM, astronomicalRangeM);
        if (surgeBlock.isEmpty()) {
            return withTide;
        }
        return PromptUtils.insertBeforeSuffix(withTide, getPromptSuffix(), surgeBlock);
    }

    /**
     * Appends the tide data block to the message builder.
     *
     * @param sb   the string builder to append to
     * @param tide the tide snapshot (must be non-null with non-null tideState)
     */
    private void appendTideBlock(StringBuilder sb, TideSnapshot tide) {
        StringBuilder tideStr = new StringBuilder();

        if (tide.lunarTideType() != null) {
            tideStr.append(tide.lunarTideType().name().replace("_", " "));
        } else {
            tideStr.append("Regular Tide");
        }

        if (tide.statisticalSize() != null) {
            String sizeLabel = switch (tide.statisticalSize()) {
                case EXTRA_EXTRA_HIGH -> ", Extra Extra High";
                case EXTRA_HIGH -> ", Extra High";
            };
            tideStr.append(sizeLabel);
        }

        tideStr.append(String.format(" (range: %.2fm, next high at %s, next low at %s)",
                tide.nextHighTideHeightMetres(),
                tide.nextHighTideTime(),
                tide.nextLowTideTime()));

        if (tide.lunarPhase() != null) {
            tideStr.append(String.format(", moon: %s", tide.lunarPhase()));
        }
        if (tide.moonAtPerigee() != null && tide.moonAtPerigee()) {
            tideStr.append(", perigee: yes");
        } else if (tide.moonAtPerigee() != null) {
            tideStr.append(", perigee: no");
        }

        if (tide.tideAligned() != null) {
            tideStr.append(String.format(", aligned: %s",
                    tide.tideAligned() ? "yes" : "no"));
        }

        sb.append("\nTide: ").append(tideStr);
    }

    /**
     * Appends the storm surge block to the message builder.
     *
     * @param sb               the string builder to append to
     * @param surge            the surge breakdown (must be significant)
     * @param adjustedRangeM   adjusted tidal range including surge, or null
     * @param astronomicalRangeM astronomical tidal range before surge, or null
     */
}
