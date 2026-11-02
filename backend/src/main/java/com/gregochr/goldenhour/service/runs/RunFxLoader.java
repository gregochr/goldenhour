package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.repository.ExchangeRateRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads {@link RunFx} from the stored rates: one read of the {@code exchange_rate} table (a row a day) and one of the
 * latest rate a run recorded. Read-only; never reaches the network.
 */
@Component
class RunFxLoader {

    private final ExchangeRateRepository rates;
    private final JobRunRepository jobRuns;

    /**
     * Constructs the loader.
     *
     * @param rates   stored daily exchange rates
     * @param jobRuns job run store, for the latest rate a run recorded
     */
    RunFxLoader(ExchangeRateRepository rates, JobRunRepository jobRuns) {
        this.rates = rates;
        this.jobRuns = jobRuns;
    }

    /**
     * Reads the rates.
     *
     * @return the rates for pricing runs
     */
    RunFx load() {
        List<Double> latest = jobRuns.findLatestExchangeRates(PageRequest.of(0, 1));
        return new RunFx(rates.findAll(), latest.isEmpty() ? null : latest.get(0));
    }
}
