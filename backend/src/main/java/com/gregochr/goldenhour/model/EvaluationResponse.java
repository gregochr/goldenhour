package com.gregochr.goldenhour.model;

/**
 * JSON schema DTO for Claude structured output.
 *
 * <p>Field names use snake_case to match the JSON keys in the evaluation prompt
 * and the structured output schema sent to the Anthropic API. The SDK derives
 * the JSON schema from these Java field names.
 *
 * @param rating      overall potential rating (1-5)
 * @param fiery_sky   dramatic colour potential (0-100)
 * @param golden_hour overall light quality (0-100)
 * @param summary     2-sentence explanation
 */
public record EvaluationResponse(
        Integer rating,
        int fiery_sky,
        int golden_hour,
        String summary
) { }
