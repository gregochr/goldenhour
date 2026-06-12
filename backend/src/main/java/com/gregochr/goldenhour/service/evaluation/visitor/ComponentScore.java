package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.ForecastType;

/**
 * One visitor's exposed contribution to a location's combined rating — the
 * {@link Visitor#type() component type}, its native 1–5 {@code score}, and the
 * visitor-authored {@code summary} clause (deterministic visitors author a one-line
 * explanation of their state; {@link SkyVisitor} re-exposes Claude's prose, and a
 * {@code null} clause is permitted).
 *
 * <p>Introduced in Pass 2 of the forecast-score re-architecture. Before Pass 2 the
 * combiner discarded the per-visitor scores and returned only the averaged rating; this
 * record is how those component scores are surfaced so the dual-write hook can persist
 * them to {@code forecast_score}. The combined rating remains a serving-path product
 * with no component row — see {@code docs/engineering/forecast-score-schema-investigation.md}.
 *
 * @param type    the score product this component is (e.g. {@link ForecastType#SKY},
 *                {@link ForecastType#TIDAL})
 * @param score   the visitor's native-scale score (1–5 for the combiner peers)
 * @param summary the visitor-authored explanation of this component, or {@code null}
 */
public record ComponentScore(ForecastType type, int score, String summary) {
}
