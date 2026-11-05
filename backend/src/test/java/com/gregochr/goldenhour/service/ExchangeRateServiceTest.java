package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ExchangeRateEntity;
import com.gregochr.goldenhour.repository.ExchangeRateRepository;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
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

    @Test
    @DisplayName("getRate() fetches from API and caches when not in DB")
    void getRate_fetchesFromApi_whenNotInDb() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(exchangeRateRepository.findByRateDate(today)).thenReturn(Optional.empty());
        RestClientMocks.stubGet(restClient, String.class, "{\"rates\":{\"GBP\":0.81}}");
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
        RestClientMocks.stubGet(restClient, String.class, "{\"rates\":{\"GBP\":0.77}}");
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
    @DisplayName("getRate() asks the latest endpoint for a future date and caches the result under that date")
    void getRate_futureDate_usesLatestEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateService service = new ExchangeRateService(
                exchangeRateRepository, builder.build(), new ObjectMapper());
        LocalDate future = LocalDate.now(ZoneOffset.UTC).plusDays(3);
        when(exchangeRateRepository.findByRateDate(future)).thenReturn(Optional.empty());
        server.expect(requestTo("https://api.frankfurter.dev/v1/latest?base=USD&symbols=GBP"))
                .andRespond(withSuccess("{\"rates\":{\"GBP\":0.83}}", MediaType.APPLICATION_JSON));

        double rate = service.getRate(future);

        assertThat(rate).isEqualTo(0.83);
        server.verify();
        ArgumentCaptor<ExchangeRateEntity> saved = ArgumentCaptor.forClass(ExchangeRateEntity.class);
        verify(exchangeRateRepository).save(saved.capture());
        assertThat(saved.getValue().getRateDate()).isEqualTo(future);
        assertThat(saved.getValue().getGbpPerUsd()).isEqualTo(0.83);
    }

    @Test
    @DisplayName("getRate() asks the dated endpoint for a past date")
    void getRate_pastDate_usesDatedEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateService service = new ExchangeRateService(
                exchangeRateRepository, builder.build(), new ObjectMapper());
        LocalDate past = LocalDate.of(2026, 1, 15);
        when(exchangeRateRepository.findByRateDate(past)).thenReturn(Optional.empty());
        server.expect(requestTo("https://api.frankfurter.dev/v1/2026-01-15?base=USD&symbols=GBP"))
                .andRespond(withSuccess("{\"rates\":{\"GBP\":0.77}}", MediaType.APPLICATION_JSON));

        assertThat(service.getRate(past)).isEqualTo(0.77);
        server.verify();
    }

    @Test
    @DisplayName("getRate() falls back when the response has no rates block, caching nothing")
    void getRate_responseWithoutRates_fallsBack() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ExchangeRateService service = serviceAnswering("{\"base\":\"USD\"}");
        when(exchangeRateRepository.findByRateDate(today)).thenReturn(Optional.empty());
        when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.empty());

        assertThat(service.getRate(today)).isEqualTo(0.79);
        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    @DisplayName("getRate() falls back when the rates block has no GBP entry")
    void getRate_ratesWithoutGbp_fallsBack() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ExchangeRateService service = serviceAnswering("{\"rates\":{\"EUR\":0.92}}");
        ExchangeRateEntity previous = ExchangeRateEntity.builder()
                .rateDate(today.minusDays(2)).gbpPerUsd(0.76)
                .fetchedAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        when(exchangeRateRepository.findByRateDate(today)).thenReturn(Optional.empty());
        when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.of(previous));

        assertThat(service.getRate(today)).isEqualTo(0.76);
    }

    private ExchangeRateService serviceAnswering(String json) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.frankfurter.dev/v1/latest?base=USD&symbols=GBP"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        return new ExchangeRateService(exchangeRateRepository, builder.build(), new ObjectMapper());
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
