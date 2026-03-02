package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ExchangeRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Spring Data repository for {@link ExchangeRateEntity}.
 */
public interface ExchangeRateRepository extends JpaRepository<ExchangeRateEntity, Long> {

    /**
     * Finds the exchange rate for a specific date.
     *
     * @param rateDate the date to look up
     * @return the exchange rate entity, if cached
     */
    Optional<ExchangeRateEntity> findByRateDate(LocalDate rateDate);

    /**
     * Finds the most recently cached exchange rate (fallback when today's rate is unavailable).
     *
     * @return the most recent exchange rate entity
     */
    Optional<ExchangeRateEntity> findTopByOrderByRateDateDesc();
}
