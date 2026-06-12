package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One normalised component-score row — see V108 and
 * docs/engineering/forecast-score-schema-investigation.md.
 *
 * <p>Grain is the component: (forecast type, location, evaluation date,
 * event). The nightly pipeline re-evaluates the same (location, date,
 * event) across cycles, so writers UPSERT against
 * {@code uq_forecast_score_component} — latest evaluation wins, matching
 * {@code cached_evaluation} semantics. {@code pipelineRunId} is provenance
 * only, never part of the key, and null for sync-path (admin) writes.
 *
 * <p>Written by the Pass 2 dual-write ({@code ForecastScoreWriter}) behind the
 * {@code photocast.forecast-score.dual-write} flag. Nothing reads it yet — the
 * read migration is Pass 4, gated on the reconciliation queries in
 * {@code docs/engineering/forecast-score-reconciliation.md}.
 */
@Entity
@Table(name = "forecast_score",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_forecast_score_component",
                columnNames = {"forecast_type_id", "location_id",
                        "evaluation_date", "event_type"}),
        indexes = {
                @Index(name = "idx_forecast_score_location_date",
                        columnList = "location_id, evaluation_date"),
                @Index(name = "idx_forecast_score_type_date",
                        columnList = "forecast_type_id, evaluation_date")
        })
@Getter
@Setter
@NoArgsConstructor
public class ForecastScoreEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Which score product this row is — the V107 lookup id. Stored as a
     * plain column (house style, cf. {@code PipelineRunPickEntity}); use
     * {@link #getForecastType()}/{@link #setForecastType(ForecastType)} to
     * work in enum terms. (A converter mapping the enum directly makes
     * Hibernate emit a check constraint H2 cannot evaluate, which breaks
     * local dev and the H2 test slice.)
     */
    @Column(name = "forecast_type_id", nullable = false)
    private Long forecastTypeId;

    /** The location this component score belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private LocationEntity location;

    /** The calendar date the score forecasts. */
    @Column(name = "evaluation_date", nullable = false)
    private LocalDate evaluationDate;

    /** SUNRISE or SUNSET. HOURLY is wildlife-only and never written here. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private TargetType eventType;

    /**
     * Native-scale score; interpret via {@link ForecastType#getScaleMax()}.
     * Only 1–5 types are combiner peers; 0–100 types are display products.
     */
    @Column(name = "score", nullable = false)
    private Integer score;

    /**
     * User-facing prose: Claude's summary on SKY rows, the deterministic state clause on TIDAL
     * rows (Pass 2 — the tide's rebuilt narrative channel), null on the 0–100 display products.
     */
    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    /** Provenance: the orchestrated cycle that wrote this row, if any. */
    @Column(name = "pipeline_run_id")
    private Long pipelineRunId;

    /** When the evaluation producing this score ran. */
    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    /**
     * @return the score product this row is, resolved from the lookup id
     */
    public ForecastType getForecastType() {
        return forecastTypeId == null ? null : ForecastType.fromId(forecastTypeId);
    }

    /**
     * Sets the score product by storing its lookup id.
     *
     * @param forecastType the score product, or null
     */
    public void setForecastType(ForecastType forecastType) {
        this.forecastTypeId = forecastType == null ? null : forecastType.getId();
    }
}
