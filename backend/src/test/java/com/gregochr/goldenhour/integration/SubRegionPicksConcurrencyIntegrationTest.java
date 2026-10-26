package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.UserSubRegionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two transactions against a real Postgres: a picks PUT waits for the reader's row lock, then decides from the
 * picks the first transaction committed (sub-regions-plan §4.3, concurrency). Without the lock the second PUT
 * would see a reader with no picks, treat its request as a first choice and insert a fourth pick over the limit.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine.
 */
class SubRegionPicksConcurrencyIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private UserSubRegionService service;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long userId;

    @AfterEach
    void clean() {
        if (userId != null) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("a PUT that arrives while another transaction holds the reader's row waits for it, then sees the "
            + "committed picks: the reader never ends over the limit")
    void secondPutWaitsForTheRowLock() throws Exception {
        userId = users.save(AppUserEntity.builder().username("racer").email("racer@example.com").password("hash")
                .role(UserRole.LITE_USER).enabled(true).createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build())
                .getId();
        List<Long> areas = jdbc.queryForList("SELECT id FROM area WHERE kind = 'SUB_REGION' ORDER BY id LIMIT 4",
                Long.class);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            users.findByIdForUpdate(userId).orElseThrow();
            for (int i = 0; i < 3; i++) {
                jdbc.update("INSERT INTO app_user_sub_region (user_id, area_id, created_at) VALUES (?, ?, now())",
                        userId, areas.get(i));
            }
            holding.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Object> second = CompletableFuture.supplyAsync(
                () -> service.replacePicks("racer", List.of(areas.get(3))));
        Thread.sleep(1500);
        assertThat(second.isDone()).as("the second PUT is waiting on the row lock").isFalse();

        release.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);

        List<Long> held = jdbc.queryForList("SELECT area_id FROM app_user_sub_region WHERE user_id = ?",
                Long.class, userId);
        assertThat(held).as("it saw three picks, so its request was a swap, not a first choice")
                .containsExactly(areas.get(3));
    }
}
