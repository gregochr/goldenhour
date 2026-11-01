package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.model.PrefetchCall;

import java.util.List;

/**
 * What the batch weather pre-fetch did in one collect pass: the Open-Meteo calls it made, how many
 * locations came back, and why it gave up if it did. Carried on the collected tasks so the
 * scheduled batch can write the calls onto a job run once one exists.
 *
 * @param calls              the Open-Meteo calls made, in order (never null)
 * @param locationsRequested unique locations asked for
 * @param locationsReturned  unique locations that came back with a forecast
 * @param abortReason        why the pre-fetch aborted the cycle, or {@code null} if it did not
 */
public record WeatherPrefetchReport(
        List<PrefetchCall> calls,
        int locationsRequested,
        int locationsReturned,
        String abortReason) {

    /**
     * Canonical constructor; defensively copies the call list.
     *
     * @param calls              the Open-Meteo calls made
     * @param locationsRequested unique locations asked for
     * @param locationsReturned  unique locations returned
     * @param abortReason        abort reason, or {@code null}
     */
    public WeatherPrefetchReport {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    /**
     * A report for a pass that never reached the pre-fetch.
     *
     * @return an empty report
     */
    public static WeatherPrefetchReport none() {
        return new WeatherPrefetchReport(List.of(), 0, 0, null);
    }

    /**
     * Whether the pre-fetch ran, that is whether it made any call or asked for any location.
     *
     * @return {@code true} if there is something to record
     */
    public boolean attempted() {
        return !calls.isEmpty() || locationsRequested > 0;
    }

    /**
     * Whether the pre-fetch aborted the cycle.
     *
     * @return {@code true} if an abort reason is set
     */
    public boolean aborted() {
        return abortReason != null;
    }
}
