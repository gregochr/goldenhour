package com.gregochr.goldenhour.model;

import com.anthropic.models.messages.CacheMissReason;
import com.anthropic.models.messages.Diagnostics;
import com.anthropic.models.messages.Message;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Optional;

/**
 * What the Anthropic API said about a response's prompt-cache behaviour, kept beside {@link
 * TokenUsage} and never folded into it: the cost maths read the token counts alone.
 *
 * <p>The API reports this only when the request carried a {@code diagnostics} object <em>and</em>
 * named a {@code previous_message_id} to compare against (an opt-in with a null id has nothing to
 * compare, so its response is always empty). It then describes the first point where this request
 * diverged from that previous one: {@code model_changed}, {@code system_changed}, {@code
 * tools_changed} or {@code messages_changed} (each with an estimate of the input tokens that fell
 * after the divergence), or {@code previous_message_not_found} / {@code unavailable} when no
 * comparison was produced. A comparison that found no divergence is reported as nothing at all, and
 * a comparison still running when the response was serialised as a present-but-empty object.
 *
 * <p>Persisted as compact JSON in {@code api_call_log.cache_diagnostics} (only when {@link
 * #isPresent()}); the JSON is the SDK's shape reduced to what it carries today, so a new field
 * needs no migration.
 *
 * @param status             whether the API reported nothing, an unfinished comparison or a reason
 * @param reason             the {@code cache_miss_reason.type} the API reported, or {@code null}
 *                           unless {@code status} is {@link Status#MISS}
 * @param missedInputTokens  the API's estimate of the input tokens after the divergence, or
 *                           {@code null} when the reason carries none (or none was reported)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CacheDiagnostics(Status status, String reason, Long missedInputTokens) {

    /** What the response's {@code diagnostics} field held. */
    public enum Status {
        /** The field was absent: no opt-in, nothing to compare, or a comparison found no divergence. */
        NONE,
        /** The field was present with no reason: the comparison was still running. Inconclusive. */
        PENDING,
        /** The field carried a {@code cache_miss_reason}. */
        MISS
    }

    /** Sentinel for a response that carried no diagnostics; never persisted. */
    public static final CacheDiagnostics EMPTY = new CacheDiagnostics(Status.NONE, null, null);

    /** Reason recorded when the API sent a reason whose type this SDK cannot name. */
    static final String UNKNOWN_REASON = "unknown";

    /**
     * Reads the diagnostics off an SDK response.
     *
     * @param message the SDK response, or {@code null}
     * @return the diagnostics, {@link #EMPTY} when the response carried none
     */
    public static CacheDiagnostics from(Message message) {
        return message == null ? EMPTY : from(message.diagnostics());
    }

    /**
     * Reads the diagnostics off the SDK's optional {@code diagnostics} field.
     *
     * @param diagnostics the field as the SDK exposes it
     * @return the diagnostics, {@link #EMPTY} when absent
     */
    public static CacheDiagnostics from(Optional<Diagnostics> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return EMPTY;
        }
        Optional<CacheMissReason> reason = diagnostics.get().cacheMissReason();
        if (reason.isEmpty()) {
            return new CacheDiagnostics(Status.PENDING, null, null);
        }
        return new CacheDiagnostics(Status.MISS, typeOf(reason.get()),
                reason.get().cacheMissedInputTokens().orElse(null));
    }

    /** The reason's {@code type} string; an unnamed type must never fail the logging of a call. */
    private static String typeOf(CacheMissReason reason) {
        try {
            String type = reason.type().asString();
            return type == null || type.isBlank() ? UNKNOWN_REASON : type;
        } catch (RuntimeException e) {
            return UNKNOWN_REASON;
        }
    }

    /**
     * Whether the API reported anything worth keeping.
     *
     * @return {@code false} for {@link #EMPTY}
     */
    @JsonIgnore
    public boolean isPresent() {
        return status != Status.NONE;
    }

    /**
     * A short, log-friendly reading: {@code none}, {@code pending} or the reason with its token
     * estimate.
     *
     * @return the summary
     */
    public String summary() {
        return switch (status) {
            case NONE -> "none";
            case PENDING -> "pending";
            case MISS -> missedInputTokens == null ? reason : reason + " (" + missedInputTokens + " tokens)";
        };
    }
}
