package com.gregochr.goldenhour.model;

/**
 * Result of weather triage evaluation — indicates why a forecast task was triaged out.
 *
 * @param reason        human-readable explanation (e.g. "Low cloud cover 85% — sun blocked")
 * @param rule          the internal triage rule that triggered the skip
 * @param triageReason  the categorised user-facing reason (enum), mapped 1:1 from {@code rule}
 */
public record TriageResult(String reason, TriageRule rule, TriageReason triageReason) {

    /**
     * Convenience constructor that derives {@code triageReason} from the rule via
     * {@link TriageReason#fromRule(TriageRule)}.
     *
     * @param reason human-readable explanation
     * @param rule   the internal triage rule
     */
    public TriageResult(String reason, TriageRule rule) {
        this(reason, rule, TriageReason.fromRule(rule));
    }
}
