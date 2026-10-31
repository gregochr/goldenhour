package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideFetchEntity;
import com.gregochr.goldenhour.repository.TideFetchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes {@code tide_fetch} provenance rows in a transaction of their own, so a diagnostic can
 * neither fail nor roll back the tide fetch it describes.
 *
 * <p>The tide fetch runs inside a transaction (the delete and the new extremes). A provenance
 * INSERT that joined it and failed would mark it rollback-only and lose the extremes. Each write
 * therefore runs in a {@code REQUIRES_NEW} transaction (a {@link TransactionTemplate} rather than
 * {@code @Transactional}, because the call is made from inside a synchronization callback and must
 * not depend on a self-invocation proxy), and every failure is caught and logged.
 *
 * <p><strong>Ordering.</strong> A success row exists only if the extremes committed: inside an
 * active transaction it is written in {@code afterCommit}. A failure row describes a fetch that
 * stored nothing, so it is written in {@code afterCompletion} whatever the outcome. With no active
 * transaction synchronization the row is written immediately.
 */
@Component
public class TideFetchRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(TideFetchRecorder.class);

    private final TideFetchRepository repository;
    private final TransactionTemplate isolated;

    /**
     * Constructs the recorder.
     *
     * @param repository         the provenance repository
     * @param transactionManager the transaction manager used to open the isolated transaction
     */
    public TideFetchRecorder(TideFetchRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records one provenance row, after the surrounding transaction (if any) has settled.
     *
     * <p>Never throws.
     *
     * @param row the row to write; its {@code success} flag decides when it is written
     */
    public void record(TideFetchEntity row) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            write(row);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (row.isSuccess()) {
                    write(row);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (!row.isSuccess()) {
                    write(row);
                }
            }
        });
    }

    private void write(TideFetchEntity row) {
        try {
            isolated.executeWithoutResult(status -> repository.save(row));
        } catch (RuntimeException e) {
            LOG.warn("Could not record the {} WorldTides fetch of location {} — the fetch itself is unaffected",
                    row.getReason(), row.getLocationId(), e);
        }
    }
}
