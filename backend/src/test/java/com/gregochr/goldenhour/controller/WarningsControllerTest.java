package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.SimulateWarningRequest;
import com.gregochr.goldenhour.model.WarningDto;
import com.gregochr.goldenhour.model.WarningsAdminStatus;
import com.gregochr.goldenhour.model.WarningsResponse;
import com.gregochr.goldenhour.service.warnings.WarningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WarningsController} and {@link WarningsAdminController}: delegation only, plus the role and
 * status-code declarations that the API reference documents.
 */
@ExtendWith(MockitoExtension.class)
class WarningsControllerTest {

    @Mock
    private WarningService service;

    @Test
    @DisplayName("GET /api/warnings serves exactly what the service assembles")
    void servesServiceResponse() {
        WarningsResponse response = new WarningsResponse(true, Instant.parse("2031-03-10T12:00:00Z"), false,
                List.of());
        when(service.served()).thenReturn(response);

        assertThat(new WarningsController(service).getWarnings()).isSameAs(response);
    }

    @Test
    @DisplayName("the shared endpoint carries no admin role requirement, so every signed-in role can read it")
    void sharedEndpointNotAdminGated() {
        assertThat(WarningsController.class.getAnnotation(PreAuthorize.class)).isNull();
    }

    @Test
    @DisplayName("the status endpoint returns the service's admin status")
    void adminStatus() {
        WarningsAdminStatus status = new WarningsAdminStatus(true, true, true, null, null, true, 0, 0, Map.of(),
                List.of());
        when(service.adminStatus()).thenReturn(status);

        assertThat(new WarningsAdminController(service).status()).isSameAs(status);
    }

    @Test
    @DisplayName("the simulate endpoint passes the body to the service and answers 201")
    void simulate() throws NoSuchMethodException {
        SimulateWarningRequest request = new SimulateWarningRequest("Amber", null, null, 6, 54.5, -1.5);
        WarningDto stored = new WarningDto("SIM-1", 1, "Amber", List.of("WIND"), null, null, null, null, null, null,
                null, List.of(), null, List.of(), null, null, null, null, Set.of(), true);
        when(service.simulate(request)).thenReturn(stored);

        assertThat(new WarningsAdminController(service).simulate(request)).isSameAs(stored);
        ResponseStatus status = WarningsAdminController.class
                .getMethod("simulate", SimulateWarningRequest.class).getAnnotation(ResponseStatus.class);
        assertThat(status.value()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("the admin controller is ADMIN-only")
    void adminControllerIsAdminOnly() {
        PreAuthorize gate = WarningsAdminController.class.getAnnotation(PreAuthorize.class);

        assertThat(gate.value()).isEqualTo("hasRole('ADMIN')");
    }
}
