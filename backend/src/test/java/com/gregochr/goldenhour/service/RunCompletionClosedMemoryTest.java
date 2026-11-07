package com.gregochr.goldenhour.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gregochr.goldenhour.entity.JobRunEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The bounded memory of closed runs in {@link RunCompletion}: it remembers the most recent 256 closed run ids, so
 * an abort of a recently closed run never closes it twice, while the oldest are forgotten.
 */
class RunCompletionClosedMemoryTest {

    private static final int REMEMBERED = 256;

    private JobRunService jobRunService;
    private RunCompletion completion;

    @BeforeEach
    void setUp() {
        jobRunService = mock(JobRunService.class);
        completion = new RunCompletion(jobRunService, mock(RunProgressTracker.class),
                mock(ApplicationEventPublisher.class));
    }

    private static JobRunEntity run(long id) {
        return JobRunEntity.builder().id(id).build();
    }

    @Test
    @DisplayName("the oldest closed run is forgotten once more than 256 have closed; recent ones are not closed again")
    void abort_oldestClosedRunIsForgotten() {
        JobRunEntity oldest = run(1L);
        JobRunEntity recent = run(2L);
        completion.abort(oldest, "stopped");
        completion.abort(recent, "stopped");
        for (long id = 3; id <= REMEMBERED + 1; id++) {
            completion.abort(run(id), "stopped");
        }
        verify(jobRunService, times(1)).completeRun(oldest, 0, 0);

        // Recent first: re-closing the oldest would push the next-oldest out of the memory in turn.
        completion.abort(recent, "stopped again");
        completion.abort(oldest, "stopped again");

        // Run 1 fell out of the memory and is closed a second time; run 2 is still remembered.
        verify(jobRunService, times(2)).completeRun(oldest, 0, 0);
        verify(jobRunService, times(1)).completeRun(recent, 0, 0);
    }
}
