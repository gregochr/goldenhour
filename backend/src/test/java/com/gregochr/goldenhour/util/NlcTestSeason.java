package com.gregochr.goldenhour.util;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.service.NlcClarityService;

import java.time.LocalDate;

/** Test support: an {@link NlcClarityService} whose season answer is fixed. */
public final class NlcTestSeason {

    private NlcTestSeason() {
    }

    /**
     * Returns a mock {@link NlcClarityService} for which every date is in season.
     *
     * @return the mock
     */
    public static NlcClarityService inSeason() {
        return withSeason(true);
    }

    /**
     * Returns a mock {@link NlcClarityService} for which every date is (or is not) in season.
     *
     * @param inSeason the answer {@code isNlcSeason} gives for any date
     * @return the mock
     */
    public static NlcClarityService withSeason(boolean inSeason) {
        NlcClarityService service = mock(NlcClarityService.class);
        when(service.isNlcSeason(any(LocalDate.class))).thenReturn(inSeason);
        return service;
    }
}
