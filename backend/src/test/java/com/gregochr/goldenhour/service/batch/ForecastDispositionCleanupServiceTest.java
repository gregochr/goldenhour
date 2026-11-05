package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.service.DynamicSchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ForecastDispositionCleanupService}: the scheduler target it registers
 * must be the disposition prune.
 */
@ExtendWith(MockitoExtension.class)
class ForecastDispositionCleanupServiceTest {

    @Mock
    private ForecastDispositionService dispositionService;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    @Test
    @DisplayName("registerJob wires the disposition_cleanup scheduler key to pruneStale")
    void registerJob_registersPruneStaleUnderTheSeededKey() {
        ForecastDispositionCleanupService service =
                new ForecastDispositionCleanupService(dispositionService, dynamicSchedulerService);

        service.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(dynamicSchedulerService).registerJobTarget(eq("disposition_cleanup"), target.capture());
        target.getValue().run();
        verify(dispositionService).pruneStale();
    }
}
