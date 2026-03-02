package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ExchangeRateEntity;
import com.gregochr.goldenhour.repository.ExchangeRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Fetches and caches daily USD-to-GBP exchange rates from the Frankfurter API (ECB data).
 *
 * <p>Rates are cached in the {@code exchange_rate} table. If the API is unavailable,
 * falls back to the most recently cached rate.
 */
@Service
public class ExchangeRateService {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeRateService.class);
    private static final String FRANKFURTER_LATEST_URL =
            "https://api.frankfurter.dev/v1/latest?base=USD&symbols=GBP";
    private static final String FRANKFURTER_HISTORICAL_URL =
            "https://api.frankfurter.dev/v1/%s?base=USD&symbols=GBP";

    /** Fallback rate if no cached rate exists (approximate GBP/USD as of March 2026). */
    private static final double FALLBACK_RATE = 0.79;

    private final ExchangeRateRepository exchangeRateRepository;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs an {@code ExchangeRateService}.
     *
     * @param exchangeRateRepository repository for cached exchange rates
     * @param restClient             shared HTTP client
     * @param objectMapper           Jackson mapper for parsing API responses
     */
    public ExchangeRateService(ExchangeRateRepository exchangeRateRepository,
            RestClient restClient, ObjectMapper objectMapper) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns today's GBP-per-USD exchange rate.
     *
     * <p>Checks the DB cache first. If missing, fetches from the Frankfurter API
     * and caches the result. Falls back to the most recent cached rate if the API
     * is unavailable.
     *
     * @return the GBP-per-USD rate (e.g. 0.79 means $1 = £0.79)
     */
    public double getCurrentRate() {
        return getRate(LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * Returns the GBP-per-USD exchange rate for a specific date.
     *
     * <p>Looks up in the DB cache first. If missing, fetches from the Frankfurter
     * historical API. Falls back to the most recent cached rate on failure.
     *
     * @param date the date to get the rate for
     * @return the GBP-per-USD rate
     */
    public double getRate(LocalDate date) {
        // Check DB cache
        Optional<ExchangeRateEntity> cached = exchangeRateRepository.findByRateDate(date);
        if (cached.isPresent()) {
            return cached.get().getGbpPerUsd();
        }

        // Fetch from API
        try {
            double rate = fetchFromApi(date);
            ExchangeRateEntity entity = ExchangeRateEntity.builder()
                    .rateDate(date)
                    .gbpPerUsd(rate)
                    .fetchedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build();
            exchangeRateRepository.save(entity);
            LOG.info("Exchange rate cached: {} GBP/USD for {}", rate, date);
            return rate;
        } catch (Exception e) {
            LOG.warn("Failed to fetch exchange rate for {}: {}. Falling back to most recent cached rate.",
                    date, e.getMessage());
            return getFallbackRate();
        }
    }

    private double fetchFromApi(LocalDate date) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String url = date.equals(today) || date.isAfter(today)
                ? FRANKFURTER_LATEST_URL
                : String.format(FRANKFURTER_HISTORICAL_URL, date);

        String json = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(json);
        JsonNode rates = root.get("rates");
        if (rates == null || !rates.has("GBP")) {
            throw new IllegalStateException("Frankfurter response missing GBP rate: " + json);
        }
        return rates.get("GBP").asDouble();
    }

    private double getFallbackRate() {
        return exchangeRateRepository.findTopByOrderByRateDateDesc()
                .map(ExchangeRateEntity::getGbpPerUsd)
                .orElse(FALLBACK_RATE);
    }
}
