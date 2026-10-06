package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.repository.AskUsageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AskUsageStore} on H2 with the schema generated from the entity: the atomic reservation, the
 * two ceilings and which one is named, the first-question insert race, and the refund's three rules
 * (the reserved date, never below zero, never {@code engine_calls}). The tests run outside a test
 * transaction ({@code NOT_SUPPORTED}) so each repository call commits as it does in production and
 * the threads genuinely race. The production SQL dialect is exercised by V166's Testcontainers test
 * in CI.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AskUsageStoreTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final long USER = 41L;

    @Autowired
    private AskUsageRepository repository;

    private AskUsageStore store() {
        return new AskUsageStore(repository);
    }

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("the first question creates the row and takes both counters")
    void firstQuestion() {
        assertThat(store().reserve(USER, DAY, 3, 9)).isEqualTo(AskUsageStore.Reservation.RESERVED);

        assertThat(store().read(USER, DAY)).isEqualTo(new AskUsageStore.Usage(1, 1));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("nothing asked means no usage, not an error")
    void noRow() {
        assertThat(store().read(USER, DAY)).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @Test
    @DisplayName("the allowance boundary: limit-1 questions leave one, the limit-th takes it, the next is "
            + "ALLOWANCE_EXHAUSTED")
    void allowanceBoundary() {
        AskUsageStore store = store();
        assertThat(store.reserve(USER, DAY, 3, 9)).isEqualTo(AskUsageStore.Reservation.RESERVED);
        assertThat(store.reserve(USER, DAY, 3, 9)).isEqualTo(AskUsageStore.Reservation.RESERVED);
        assertThat(store.reserve(USER, DAY, 3, 9)).isEqualTo(AskUsageStore.Reservation.RESERVED);
        assertThat(store.reserve(USER, DAY, 3, 9)).isEqualTo(AskUsageStore.Reservation.ALLOWANCE_EXHAUSTED);

        assertThat(store.read(USER, DAY)).as("a refused reservation takes nothing").isEqualTo(
                new AskUsageStore.Usage(3, 3));
    }

    @Test
    @DisplayName("a limit of 0 reserves nothing and names the allowance")
    void limitZero() {
        assertThat(store().reserve(USER, DAY, 0, 0)).isEqualTo(AskUsageStore.Reservation.ALLOWANCE_EXHAUSTED);
        assertThat(repository.count()).as("the row was made, with nothing taken").isEqualTo(1);
        assertThat(store().read(USER, DAY)).isEqualTo(new AskUsageStore.Usage(0, 0));
    }

    @Test
    @DisplayName("engine_calls is never refunded, so a user whose every question is unanswerable hits "
            + "DAILY_LIMIT at 3 x the allowance with used still at 0")
    void dailyLimitWithUsedAtZero() {
        AskUsageStore store = store();
        for (int i = 1; i <= 9; i++) {
            assertThat(store.reserve(USER, DAY, 3, 9)).as("call %d", i).isEqualTo(AskUsageStore.Reservation.RESERVED);
            assertThat(store.refund(USER, DAY)).as("refund %d", i).isTrue();
        }

        assertThat(store.read(USER, DAY)).isEqualTo(new AskUsageStore.Usage(0, 9));
        assertThat(store.reserve(USER, DAY, 3, 9)).isEqualTo(AskUsageStore.Reservation.DAILY_LIMIT);
        assertThat(store.read(USER, DAY)).isEqualTo(new AskUsageStore.Usage(0, 9));
    }

    @Test
    @DisplayName("ceiling N-1 passes, N is refused: the engine-call ceiling is checked in the same statement")
    void ceilingBoundary() {
        AskUsageStore store = store();
        for (int i = 0; i < 8; i++) {
            store.reserve(USER, DAY, 100, 9);
            store.refund(USER, DAY);
        }
        assertThat(store.reserve(USER, DAY, 100, 9)).isEqualTo(AskUsageStore.Reservation.RESERVED);
        assertThat(store.reserve(USER, DAY, 100, 9)).isEqualTo(AskUsageStore.Reservation.DAILY_LIMIT);
    }

    @Test
    @DisplayName("when both ceilings are reached the allowance is the one named")
    void allowanceNamedFirst() {
        AskUsageStore store = store();
        store.reserve(USER, DAY, 1, 1);

        assertThat(store.reserve(USER, DAY, 1, 1)).isEqualTo(AskUsageStore.Reservation.ALLOWANCE_EXHAUSTED);
    }

    @Test
    @DisplayName("a refund gives back one charged question and leaves engine_calls alone")
    void refundTakesUsedOnly() {
        AskUsageStore store = store();
        store.reserve(USER, DAY, 3, 9);
        store.reserve(USER, DAY, 3, 9);

        assertThat(store.refund(USER, DAY)).isTrue();

        assertThat(store.read(USER, DAY)).isEqualTo(new AskUsageStore.Usage(1, 2));
    }

    @Test
    @DisplayName("a refund never goes below zero, and with no row it does nothing")
    void refundNeverBelowZero() {
        AskUsageStore store = store();
        assertThat(store.refund(USER, DAY)).as("no row").isFalse();

        store.reserve(USER, DAY, 3, 9);
        assertThat(store.refund(USER, DAY)).isTrue();
        assertThat(store.refund(USER, DAY)).as("already zero").isFalse();
        assertThat(store.refund(USER, DAY)).isFalse();

        assertThat(store.read(USER, DAY)).isEqualTo(new AskUsageStore.Usage(0, 1));
    }

    @Test
    @DisplayName("a refund lands on the date it was reserved on, not on another day's row")
    void refundTargetsTheReservedDate() {
        AskUsageStore store = store();
        LocalDate next = DAY.plusDays(1);
        store.reserve(USER, DAY, 3, 9);
        store.reserve(USER, next, 3, 9);

        assertThat(store.refund(USER, DAY)).isTrue();

        assertThat(store.read(USER, DAY)).isEqualTo(new AskUsageStore.Usage(0, 1));
        assertThat(store.read(USER, next)).isEqualTo(new AskUsageStore.Usage(1, 1));
    }

    @Test
    @DisplayName("users and days are counted separately")
    void separateRows() {
        AskUsageStore store = store();
        store.reserve(1L, DAY, 1, 3);

        assertThat(store.reserve(2L, DAY, 1, 3)).isEqualTo(AskUsageStore.Reservation.RESERVED);
        assertThat(store.reserve(1L, DAY.plusDays(1), 1, 3)).isEqualTo(AskUsageStore.Reservation.RESERVED);
        assertThat(store.reserve(1L, DAY, 1, 3)).isEqualTo(AskUsageStore.Reservation.ALLOWANCE_EXHAUSTED);
    }

    @Test
    @DisplayName("reserve is atomic at used = limit - 1: two threads, one slot, exactly one passes")
    void atomicAtTheLastSlot() throws Exception {
        AskUsageStore store = store();
        store.reserve(USER, DAY, 3, 9);
        store.reserve(USER, DAY, 3, 9);

        List<AskUsageStore.Reservation> results = race(2, () -> store.reserve(USER, DAY, 3, 9));

        assertThat(results).containsExactlyInAnyOrder(AskUsageStore.Reservation.RESERVED,
                AskUsageStore.Reservation.ALLOWANCE_EXHAUSTED);
        assertThat(store.read(USER, DAY).used()).isEqualTo(3);
    }

    @Test
    @DisplayName("many threads on one user never exceed the allowance")
    void neverExceedsUnderContention() throws Exception {
        AskUsageStore store = store();

        List<AskUsageStore.Reservation> results = race(16, () -> store.reserve(USER, DAY, 5, 15));

        assertThat(results.stream().filter(r -> r == AskUsageStore.Reservation.RESERVED).count()).isEqualTo(5);
        assertThat(store.read(USER, DAY)).isEqualTo(new AskUsageStore.Usage(5, 5));
    }

    @Test
    @DisplayName("the first-question insert race: two threads, one row, both succeed against a limit of 2")
    void firstQuestionInsertRace() throws Exception {
        AskUsageStore store = store();

        List<AskUsageStore.Reservation> results = race(2, () -> store.reserve(USER, DAY, 2, 6));

        assertThat(results).containsExactly(AskUsageStore.Reservation.RESERVED,
                AskUsageStore.Reservation.RESERVED);
        assertThat(repository.count()).as("one row").isEqualTo(1);
        assertThat(store.read(USER, DAY)).isEqualTo(new AskUsageStore.Usage(2, 2));
    }

    @Test
    @DisplayName("the insert race holds for a crowd on a fresh day: still one row and the exact count")
    void insertRaceWithACrowd() throws Exception {
        AskUsageStore store = store();

        race(12, () -> store.reserve(USER, DAY, 12, 36));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(store.read(USER, DAY)).isEqualTo(new AskUsageStore.Usage(12, 12));
    }

    @Test
    @DisplayName("an insert that fails and leaves no row behind (the user is gone, not a lost race) is rethrown, "
            + "and nothing is reserved")
    void insertFailureWithoutARowIsRethrown() {
        AskUsageRepository broken = mock(AskUsageRepository.class);
        when(broken.existsByUserIdAndUsageDate(USER, DAY)).thenReturn(false);
        when(broken.insertRow(USER, DAY))
                .thenThrow(new DataIntegrityViolationException("fk"));

        assertThatThrownBy(() -> new AskUsageStore(broken).reserve(USER, DAY, 3, 9))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(broken, never()).reserve(anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a reservation that cannot settle (neither counter at its limit yet the update refuses) fails "
            + "after a bounded number of tries instead of looping or naming the wrong ceiling")
    void unsettledReservationFails() {
        AskUsageRepository odd = mock(AskUsageRepository.class);
        when(odd.existsByUserIdAndUsageDate(USER, DAY)).thenReturn(true);
        when(odd.reserve(USER, DAY, 3, 9)).thenReturn(0);
        when(odd.findCountsByUserIdAndUsageDate(USER, DAY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new AskUsageStore(odd).reserve(USER, DAY, 3, 9))
                .isInstanceOf(IllegalStateException.class);
        verify(odd, times(3)).reserve(USER, DAY, 3, 9);
    }

    @Test
    @DisplayName("a refund that fails in the database is reported as not refunded and never thrown")
    void refundFailureIsSwallowed() {
        AskUsageRepository down = mock(AskUsageRepository.class);
        when(down.refund(USER, DAY)).thenThrow(new IllegalStateException("db"));

        assertThat(new AskUsageStore(down).refund(USER, DAY)).isFalse();
    }

    /** Runs {@code task} on {@code threads} threads released together and returns every result. */
    private static <T> List<T> race(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
