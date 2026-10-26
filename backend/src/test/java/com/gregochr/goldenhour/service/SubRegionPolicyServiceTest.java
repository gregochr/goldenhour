package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.SubRegionPolicyEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.SubRegionPolicyResponse;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.SubRegionPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SubRegionPolicyService}: validation of the limit against the number of sub-regions, the over-limit
 * warning count, the snapshot cache and its invalidation on save, and the default when no row exists.
 */
@ExtendWith(MockitoExtension.class)
class SubRegionPolicyServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @Mock
    private SubRegionPolicyRepository policyRepository;
    @Mock
    private AreaRepository areaRepository;
    @Mock
    private AppUserRepository userRepository;

    private SubRegionPolicyService service;

    @BeforeEach
    void setUp() {
        service = new SubRegionPolicyService(policyRepository, areaRepository, userRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SubRegionPolicyEntity row(int limit) {
        SubRegionPolicyEntity row = new SubRegionPolicyEntity();
        row.setLiteLimit((short) limit);
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        row.setUpdatedBy("migration");
        return row;
    }

    @Test
    @DisplayName("with no row the limit is the default 3")
    void noRow_isTheDefault() {
        when(policyRepository.findById((short) 1)).thenReturn(Optional.empty());

        assertThat(service.getLiteLimit()).isEqualTo(3);
    }

    @Test
    @DisplayName("the limit is read once and cached")
    void limit_isCached() {
        when(policyRepository.findById((short) 1)).thenReturn(Optional.of(row(5)));

        assertThat(service.getLiteLimit()).isEqualTo(5);
        assertThat(service.getLiteLimit()).isEqualTo(5);

        verify(policyRepository, times(1)).findById((short) 1);
    }

    @Test
    @DisplayName("saving writes the limit, the time and the admin, and the next read sees the new value")
    void update_savesAndInvalidates() {
        SubRegionPolicyEntity existing = row(3);
        when(policyRepository.findById((short) 1)).thenReturn(Optional.of(existing));
        when(areaRepository.countByKind(AreaKind.SUB_REGION)).thenReturn(24L);
        when(userRepository.countHoldingMorePicksThan(UserRole.LITE_USER, 2)).thenReturn(4L);
        assertThat(service.getLiteLimit()).isEqualTo(3);

        SubRegionPolicyResponse response = service.update(2, "chris");

        ArgumentCaptor<SubRegionPolicyEntity> saved = ArgumentCaptor.forClass(SubRegionPolicyEntity.class);
        verify(policyRepository).save(saved.capture());
        assertThat(saved.getValue().getLiteLimit()).isEqualTo((short) 2);
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo("chris");
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 0));
        assertThat(response.liteLimit()).isEqualTo(2);
        assertThat(response.maxLimit()).isEqualTo(24);
        assertThat(response.liteUsersOverLimit()).as("readers now over their limit").isEqualTo(4);
        assertThat(service.getLiteLimit()).as("re-read after the save").isEqualTo(2);
        verify(policyRepository, times(3)).findById((short) 1);
    }

    @Test
    @DisplayName("with no row yet the first save creates it")
    void update_createsTheRow() {
        when(policyRepository.findById((short) 1)).thenReturn(Optional.empty());
        when(areaRepository.countByKind(AreaKind.SUB_REGION)).thenReturn(24L);
        when(userRepository.countHoldingMorePicksThan(UserRole.LITE_USER, 6)).thenReturn(0L);

        service.update(6, "chris");

        ArgumentCaptor<SubRegionPolicyEntity> saved = ArgumentCaptor.forClass(SubRegionPolicyEntity.class);
        verify(policyRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo((short) 1);
        assertThat(saved.getValue().getLiteLimit()).isEqualTo((short) 6);
    }

    @Test
    @DisplayName("a limit below 1, above the number of sub-regions, above the table's 24, or missing is a 400, "
            + "and nothing is saved")
    void update_validation() {
        when(areaRepository.countByKind(AreaKind.SUB_REGION)).thenReturn(5L);

        assertThatThrownBy(() -> service.update(0, "chris")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The limit must be between 1 and 5 (the number of sub-regions); 0 is not");
        assertThatThrownBy(() -> service.update(6, "chris")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The limit must be between 1 and 5 (the number of sub-regions); 6 is not");
        assertThatThrownBy(() -> service.update(null, "chris")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("liteLimit is required");
        verify(policyRepository, never()).save(org.mockito.ArgumentMatchers.<SubRegionPolicyEntity>any());
    }

    @Test
    @DisplayName("more sub-regions than the table allows still caps the limit at 24")
    void update_capsAtTwentyFour() {
        when(areaRepository.countByKind(AreaKind.SUB_REGION)).thenReturn(40L);

        assertThatThrownBy(() -> service.update(25, "chris")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The limit must be between 1 and 24 (the number of sub-regions); 25 is not");
    }

    @Test
    @DisplayName("describe() refuses a candidate outside 1..the number of sub-regions, like the PUT")
    void describe_validatesTheCandidate() {
        when(policyRepository.findById((short) 1)).thenReturn(Optional.of(row(3)));
        when(areaRepository.countByKind(AreaKind.SUB_REGION)).thenReturn(5L);

        assertThatThrownBy(() -> service.describe(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The limit must be between 1 and 5 (the number of sub-regions); 0 is not");
        assertThatThrownBy(() -> service.describe(6)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The limit must be between 1 and 5 (the number of sub-regions); 6 is not");
    }

    @Test
    @DisplayName("describe() counts the readers a candidate limit would leave over, or the saved limit's")
    void describe_countsAgainstTheCandidate() {
        when(policyRepository.findById((short) 1)).thenReturn(Optional.of(row(3)));
        when(areaRepository.countByKind(AreaKind.SUB_REGION)).thenReturn(24L);
        when(userRepository.countHoldingMorePicksThan(UserRole.LITE_USER, 2)).thenReturn(7L);
        when(userRepository.countHoldingMorePicksThan(UserRole.LITE_USER, 3)).thenReturn(1L);

        SubRegionPolicyResponse candidate = service.describe(2);
        SubRegionPolicyResponse saved = service.describe(null);

        assertThat(candidate.liteLimit()).isEqualTo(3);
        assertThat(candidate.candidateLimit()).isEqualTo(2);
        assertThat(candidate.liteUsersOverLimit()).isEqualTo(7);
        assertThat(saved.candidateLimit()).isEqualTo(3);
        assertThat(saved.liteUsersOverLimit()).isEqualTo(1);
        assertThat(saved.updatedBy()).isEqualTo("migration");
        assertThat(saved.updatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 9, 0));
    }
}
