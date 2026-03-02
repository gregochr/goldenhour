package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ExchangeRateEntity;
import com.gregochr.goldenhour.repository.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExchangeRateService}.
 */
@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    @Mock
    private RestClient restClient;

    private ExchangeRateService exchangeRateService;

    @BeforeEach
    void setUp() {
        exchangeRateService = new ExchangeRateService(
                exchangeRateRepository, restClient, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private void mockRestClientResponse(String responseBody) {
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(anyString())).thenReturn((RestClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(responseBody);
    }

    @Test
    @DisplayName("getRate() fetches from API and caches when not in DB")
    void getRate_fetchesFromApi_whenNotInDb() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(exchangeRateRepository.findByRateDate(today)).thenReturn(Optional.empty());
        mockRestClientResponse("{\"rates\":{\"GBP\":0.81}}");
        when(exchangeRateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        double rate = exchangeRateService.getRate(today);

        assertThat(rate).isEqualTo(0.81);
        verify(exchangeRateRepository).save(any(ExchangeRateEntity.class));
    }

    @Test
    @DisplayName("getRate() uses historical URL for past dates")
    void getRate_usesHistoricalUrl_forPastDate() {
        LocalDate pastDate = LocalDate.of(2026, 1, 15);
        when(exchangeRateRepository.findByRateDate(pastDate)).thenReturn(Optional.empty());
        mockRestClientResponse("{\"rates\":{\"GBP\":0.77}}");
        when(exchangeRateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        double rate = exchangeRateService.getRate(pastDate);

        assertThat(rate).isEqualTo(0.77);
        verify(exchangeRateRepository).save(any(ExchangeRateEntity.class));
    }

    @Test
    @DisplayName("getCurrentRate() delegates to getRate() with today's date")
    void getCurrentRate_delegatesToGetRate() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ExchangeRateEntity cached = ExchangeRateEntity.builder()
                .rateDate(today).gbpPerUsd(0.80)
                .fetchedAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        when(exchangeRateRepository.findByRateDate(today)).thenReturn(Optional.of(cached));

        double rate = exchangeRateService.getCurrentRate();

        assertThat(rate).isEqualTo(0.80);
    }

    @Test
    @DisplayName("getRate() returns cached rate when present in DB")
    void getRate_returnsCachedRate_whenPresentInDb() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ExchangeRateEntity cached = ExchangeRateEntity.builder()
                .rateDate(today)
                .gbpPerUsd(0.79)
                .fetchedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        when(exchangeRateRepository.findByRateDate(today)).thenReturn(Optional.of(cached));

        double rate = exchangeRateService.getRate(today);

        assertThat(rate).isEqualTo(0.79);
        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    @DisplayName("getRate() falls back to most recent cached rate when API fails and no rate for date")
    void getRate_fallsBackToMostRecent_whenApiFails() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(exchangeRateRepository.findByRateDate(today)).thenReturn(Optional.empty());

        // RestClient will throw because we haven't mocked the full chain
        ExchangeRateEntity fallback = ExchangeRateEntity.builder()
                .rateDate(today.minusDays(1))
                .gbpPerUsd(0.78)
                .fetchedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.of(fallback));

        double rate = exchangeRateService.getRate(today);

        assertThat(rate).isEqualTo(0.78);
    }

    @Test
    @DisplayName("getRate() returns hardcoded fallback when no cached rates exist")
    void getRate_returnsHardcodedFallback_whenNoCachedRates() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(exchangeRateRepository.findByRateDate(today)).thenReturn(Optional.empty());
        when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.empty());

        double rate = exchangeRateService.getRate(today);

        assertThat(rate).isEqualTo(0.79);
    }
}
