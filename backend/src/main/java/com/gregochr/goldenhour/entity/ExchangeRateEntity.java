package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA entity caching daily USD-to-GBP exchange rates from the Frankfurter API.
 *
 * <p>One row per date. Used to convert micro-dollar costs to GBP for display.
 */
@Entity
@Table(name = "exchange_rate")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The date this rate applies to (unique). */
    @Column(name = "rate_date", nullable = false, unique = true)
    private LocalDate rateDate;

    /** GBP per 1 USD (e.g. 0.79 means $1 = £0.79). */
    @Column(name = "gbp_per_usd", nullable = false)
    private double gbpPerUsd;

    /** UTC timestamp when this rate was fetched from the API. */
    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}
