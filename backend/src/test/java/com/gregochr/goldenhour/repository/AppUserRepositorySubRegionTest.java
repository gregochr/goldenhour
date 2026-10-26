package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the scoped writers of {@code app_user.sub_regions_grace_until} / {@code sub_regions_changed_at} (V192)
 * on a real JPA stack (H2): the conditional lock update and its strict cutoff comparison, the grace start that
 * only ever sets once, the clears, the over-limit count, and the {@code updatable = false} mapping (a
 * whole-entity {@code save()} writes neither column). V192 itself is proven in CI by
 * {@code SubRegionPicksMigrationTest}. Every assertion reads the row through {@link JdbcTemplate}.
 */
@DataJpaTest
class AppUserRepositorySubRegionTest {

    private static final LocalDateTime STAMP = LocalDateTime.of(2026, 11, 10, 9, 30);

    @Autowired
    private AppUserRepository repository;

    @Autowired
    private AppUserSubRegionRepository picks;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;

    @BeforeEach
    void persistReader() {
        userId = persist("reader", UserRole.LITE_USER);
    }

    private Long persist(String name, UserRole role) {
        AppUserEntity user = AppUserEntity.builder().username(name).password("hash").role(role).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).email(name + "@example.com").build();
        Long id = entityManager.persistAndGetId(user, Long.class);
        entityManager.flush();
        entityManager.clear();
        return id;
    }

    private LocalDateTime changedAt() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT sub_regions_changed_at FROM app_user WHERE id = ?",
                LocalDateTime.class, userId);
    }

    private LocalDate graceUntil() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT sub_regions_grace_until FROM app_user WHERE id = ?",
                LocalDate.class, userId);
    }

    @Test
    @DisplayName("a new row has neither column set")
    void newRow_isNull() {
        assertThat(changedAt()).isNull();
        assertThat(graceUntil()).isNull();
        assertThat(repository.findById(userId).orElseThrow().getSubRegionsChangedAt()).isNull();
    }

    @Test
    @DisplayName("the conditional stamp writes when nothing is stamped")
    void conditionalStamp_writesWhenUnstamped() {
        int rows = repository.stampSubRegionsChangedIfUnlocked(userId, STAMP, LocalDateTime.of(2026, 11, 1, 0, 0));

        assertThat(rows).isEqualTo(1);
        assertThat(changedAt()).isEqualTo(STAMP);
    }

    @Test
    @DisplayName("the conditional stamp writes when the old stamp is strictly before the cutoff, and refuses "
            + "(0 rows) when it equals the cutoff or is after it")
    void conditionalStamp_cutoffIsStrict() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 11, 1, 0, 0);
        jdbc.update("UPDATE app_user SET sub_regions_changed_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 10, 31, 23, 59, 59), userId);
        assertThat(repository.stampSubRegionsChangedIfUnlocked(userId, STAMP, cutoff)).isEqualTo(1);
        assertThat(changedAt()).isEqualTo(STAMP);

        jdbc.update("UPDATE app_user SET sub_regions_changed_at = ? WHERE id = ?", cutoff, userId);
        assertThat(repository.stampSubRegionsChangedIfUnlocked(userId, STAMP, cutoff)).isZero();
        assertThat(changedAt()).as("refused: the stamp is untouched").isEqualTo(cutoff);

        jdbc.update("UPDATE app_user SET sub_regions_changed_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 11, 5, 0, 0), userId);
        assertThat(repository.stampSubRegionsChangedIfUnlocked(userId, STAMP, cutoff)).isZero();
        assertThat(changedAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 0, 0));
    }

    @Test
    @DisplayName("the unconditional stamp writes whatever is there; the clear nulls it")
    void unconditionalStampAndClear() {
        jdbc.update("UPDATE app_user SET sub_regions_changed_at = ? WHERE id = ?", STAMP.plusDays(5), userId);

        assertThat(repository.stampSubRegionsChanged(userId, STAMP)).isEqualTo(1);
        assertThat(changedAt()).isEqualTo(STAMP);
        assertThat(repository.clearSubRegionsChangedAt(userId)).isEqualTo(1);
        assertThat(changedAt()).isNull();
        assertThat(repository.clearSubRegionsChangedAt(9_999_999L)).isZero();
    }

    @Test
    @DisplayName("grace is started once: a second start leaves the first end date")
    void graceStartsOnlyOnce() {
        assertThat(repository.startSubRegionGraceIfUnset(userId, LocalDate.of(2026, 11, 1))).isEqualTo(1);
        assertThat(repository.startSubRegionGraceIfUnset(userId, LocalDate.of(2027, 1, 1))).isZero();

        assertThat(graceUntil()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("save() on an existing row writes neither column (updatable = false)")
    void save_neverWritesTheColumns() {
        AppUserEntity loaded = repository.findById(userId).orElseThrow();
        loaded.setSubRegionsGraceUntil(LocalDate.of(2030, 1, 1));
        loaded.setSubRegionsChangedAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        loaded.setEmail("new@example.com");
        repository.saveAndFlush(loaded);

        assertThat(graceUntil()).isNull();
        assertThat(changedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, userId))
                .isEqualTo("new@example.com");
    }

    @Test
    @DisplayName("clearing for the pickers of a sub-region clears only those readers' stamps")
    void clearForPickersOfOneArea() {
        Long other = persist("other", UserRole.LITE_USER);
        jdbc.update("UPDATE app_user SET sub_regions_changed_at = ?", STAMP);
        picks.insertPick(userId, 77L, STAMP);
        picks.insertPick(other, 88L, STAMP);

        assertThat(repository.clearSubRegionsChangedAtForPickersOf(77L)).isEqualTo(1);

        assertThat(changedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT sub_regions_changed_at FROM app_user WHERE id = ?",
                LocalDateTime.class, other)).isEqualTo(STAMP);
    }

    @Test
    @DisplayName("the over-limit count is of readers of the role holding strictly more than the limit")
    void overLimitCount() {
        Long heavy = persist("heavy", UserRole.LITE_USER);
        Long pro = persist("proheavy", UserRole.PRO_USER);
        for (long area = 1; area <= 3; area++) {
            picks.insertPick(heavy, area, STAMP);
            picks.insertPick(pro, area, STAMP);
        }
        picks.insertPick(userId, 1L, STAMP);

        assertThat(repository.countHoldingMorePicksThan(UserRole.LITE_USER, 2)).isEqualTo(1);
        assertThat(repository.countHoldingMorePicksThan(UserRole.LITE_USER, 3)).isZero();
        assertThat(repository.countHoldingMorePicksThan(UserRole.LITE_USER, 0)).isEqualTo(2);
        assertThat(repository.countHoldingMorePicksThan(UserRole.PRO_USER, 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("picks are read earliest first, deleted by id set and by area")
    void picksRoundTrip() {
        picks.insertPick(userId, 3L, STAMP.plusMinutes(2));
        picks.insertPick(userId, 1L, STAMP);
        picks.insertPick(userId, 2L, STAMP);

        assertThat(picks.findAreaIdsByUserId(userId)).containsExactly(1L, 2L, 3L);
        assertThat(picks.deletePicks(userId, java.util.Set.of(1L, 3L))).isEqualTo(2);
        assertThat(picks.findAreaIdsByUserId(userId)).containsExactly(2L);
        assertThat(picks.deleteByAreaId(2L)).isEqualTo(1);
        assertThat(picks.findAreaIdsByUserId(userId)).isEmpty();
    }
}
