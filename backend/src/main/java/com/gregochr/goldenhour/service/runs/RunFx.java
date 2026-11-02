package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.ExchangeRateEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * GBP-per-USD rates for pricing runs, read once from rows already stored. Nothing here fetches a rate: a Runs request
 * must never call the exchange-rate service, which fetches over the network and writes on a miss.
 *
 * <p>A run is priced with its own recorded rate. A run that recorded none (and a sky-rating eval, which never does)
 * is priced with the stored rate of the nearest earlier day, then the latest rate any run recorded, then
 * {@value #FALLBACK_GBP_PER_USD}. Conversion happens run by run, before any sum.
 */
final class RunFx {

    /** The rate of last resort, the same constant the exchange-rate service falls back to. */
    static final double FALLBACK_GBP_PER_USD = 0.79;

    /** Micro-dollars in a dollar. */
    static final double MICRO = 1_000_000.0;

    private final NavigableMap<LocalDate, Double> byDate = new TreeMap<>();
    private final double latest;

    /**
     * Builds the rates.
     *
     * @param stored     the stored daily rates
     * @param latestRate the latest rate any run recorded, or null when none did
     */
    RunFx(Collection<ExchangeRateEntity> stored, Double latestRate) {
        stored.forEach(e -> byDate.put(e.getRateDate(), e.getGbpPerUsd()));
        this.latest = latestRate != null ? latestRate : FALLBACK_GBP_PER_USD;
    }

    /**
     * The rate for a day with no recorded rate of its own.
     *
     * @param day the day
     * @return the stored rate of the nearest earlier day, else the latest run rate, else the fallback
     */
    double rateOn(LocalDate day) {
        var floor = byDate.floorEntry(day);
        return floor != null ? floor.getValue() : latest;
    }

    /**
     * The rate to price a run with.
     *
     * @param own the run's own rate, or null
     * @param day the run's day (UTC date of its start)
     * @return the own rate when it has one, else {@link #rateOn}
     */
    double rateFor(Double own, LocalDate day) {
        return own != null ? own : rateOn(day);
    }

    /**
     * Converts micro-dollars to pounds, unrounded, so sums are rounded once at the end.
     *
     * @param micro micro-dollars
     * @param rate  GBP per USD
     * @return pounds
     */
    static double gbp(long micro, double rate) {
        return micro / MICRO * rate;
    }

    /**
     * Rounds pounds to the penny.
     *
     * @param pounds pounds
     * @return the amount to two decimal places
     */
    static BigDecimal pence(double pounds) {
        return BigDecimal.valueOf(pounds).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Converts micro-dollars to dollars to the cent.
     *
     * @param micro micro-dollars
     * @return dollars to two decimal places
     */
    static BigDecimal dollars(long micro) {
        return BigDecimal.valueOf(micro / MICRO).setScale(2, RoundingMode.HALF_UP);
    }
}
