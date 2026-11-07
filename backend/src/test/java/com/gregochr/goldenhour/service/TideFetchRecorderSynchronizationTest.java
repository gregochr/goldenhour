package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gregochr.goldenhour.entity.TideFetchEntity;
import com.gregochr.goldenhour.repository.TideFetchRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * When a fetch row is written: at once when no transaction is running, after commit for a success and after
 * completion for a failure when one is, and never in a way that breaks the fetch if the write itself fails.
 */
class TideFetchRecorderSynchronizationTest {

    private TideFetchRepository repository;
    private PlatformTransactionManager transactionManager;
    private TideFetchRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(TideFetchRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        recorder = new TideFetchRecorder(repository, transactionManager);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static TideFetchEntity row(boolean success) {
        return TideFetchEntity.builder().locationId(7L).reason(TideFetchEntity.SEED).success(success).build();
    }

    @Test
    @DisplayName("with no surrounding transaction the row is written at once in its own transaction")
    void record_noTransaction_writesImmediately() {
        TideFetchEntity fetched = row(true);

        recorder.record(fetched);

        verify(repository).save(fetched);
        verify(transactionManager).getTransaction(
                org.mockito.ArgumentMatchers.argThat(def -> def != null
                        && def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    @DisplayName("a successful fetch is written after the surrounding transaction commits, not on rollback")
    void record_success_writtenAfterCommitOnly() {
        TransactionSynchronizationManager.initSynchronization();
        TideFetchEntity fetched = row(true);

        recorder.record(fetched);

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        verify(repository, never()).save(fetched);
        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(repository, never()).save(fetched);
        synchronizations.get(0).afterCommit();
        verify(repository).save(fetched);
    }

    @Test
    @DisplayName("a failed fetch is written after completion, so a rollback cannot lose it, and not after commit")
    void record_failure_writtenAfterCompletionOnly() {
        TransactionSynchronizationManager.initSynchronization();
        TideFetchEntity failed = row(false);

        recorder.record(failed);

        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCommit();
        verify(repository, never()).save(failed);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(repository).save(failed);
    }

    @Test
    @DisplayName("a successful fetch's completion callback writes nothing")
    void record_success_completionWritesNothing() {
        TransactionSynchronizationManager.initSynchronization();
        TideFetchEntity fetched = row(true);

        recorder.record(fetched);

        TransactionSynchronizationManager.getSynchronizations().get(0)
                .afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        verify(repository, never()).save(fetched);
    }

    @Test
    @DisplayName("a write that fails is logged and swallowed, the fetch itself is unaffected")
    void record_writeFails_isSwallowed() {
        TideFetchEntity fetched = row(true);
        doThrow(new IllegalStateException("db down")).when(repository).save(fetched);

        assertThatCode(() -> recorder.record(fetched)).doesNotThrowAnyException();
        verify(repository).save(fetched);
    }
}
