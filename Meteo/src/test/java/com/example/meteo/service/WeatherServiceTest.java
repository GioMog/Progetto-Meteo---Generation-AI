package com.example.meteo.service;

import com.example.meteo.model.WeatherData;
import com.example.meteo.service.WeatherService.CityNotFoundException;
import com.example.meteo.service.WeatherService.GeocodingApiException;
import com.example.meteo.service.WeatherService.WeatherApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeatherServiceTest {

    @Mock
    private WebClient.Builder webClientBuilder;

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec<?> requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private WeatherService weatherService;

    @BeforeEach
    void setUp() {
        lenient().doReturn(webClient).when(webClientBuilder).build();
        lenient().doReturn(requestHeadersUriSpec).when(webClient).get();
        lenient().doReturn(requestHeadersSpec).when(requestHeadersUriSpec).uri(anyString());
        lenient().doReturn(responseSpec).when(requestHeadersSpec).retrieve();

        weatherService = new WeatherService(webClientBuilder, new ObjectMapper());
        ReflectionTestUtils.setField(weatherService, "geocodingApiUrl", "https://geocoding-api.open-meteo.com/v1/search");
        ReflectionTestUtils.setField(weatherService, "weatherApiUrl", "https://api.open-meteo.com/v1/forecast");
    }

    @Test
    void testGetWeatherByCitySuccess() {
        String geocodingJson = "{\"results\":[{\"latitude\":41.9028,\"longitude\":12.4964,\"name\":\"Rome\"}]}";
        String weatherJson = "{\"current_weather\":{\"temperature\":20.5,\"weathercode\":0}}";

        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(geocodingJson))
                .thenReturn(Mono.just(weatherJson));

        Mono<WeatherData> result = weatherService.getWeatherByCity("Rome");

        WeatherData data = result.block();
        assertNotNull(data);
        assertEquals("Rome", data.getCity());
        assertEquals(20.5, data.getTemperature());
        assertEquals("Cielo sereno", data.getDescription());
    }

    @Test
    void testGetWeatherByCityNotFound() {
        String geocodingJson = "{\"results\":[]}";
        
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(geocodingJson));

        Mono<WeatherData> result = weatherService.getWeatherByCity("CittàInesistente");

        CityNotFoundException exception = assertThrows(CityNotFoundException.class, () -> result.block());
        assertEquals("Città non trovata nella risposta di geocoding.", exception.getMessage());
    }

    @Test
    void testGetWeatherByCityGeocodingError() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.error(new RuntimeException("API Error")));

        Mono<WeatherData> result = weatherService.getWeatherByCity("Paris");

        GeocodingApiException exception = assertThrows(GeocodingApiException.class, () -> result.block());
        assertTrue(exception.getMessage().contains("Errore durante la chiamata all'API di geocoding"));
    }

    @Test
    void testGetWeatherByCityWeatherApiError() {
        String geocodingJson = "{\"results\":[{\"latitude\":48.8566,\"longitude\":2.3522,\"name\":\"Paris\"}]}";

        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(geocodingJson))
                .thenReturn(Mono.error(new RuntimeException("Weather API Error")));

        Mono<WeatherData> result = weatherService.getWeatherByCity("Paris");

        WeatherApiException exception = assertThrows(WeatherApiException.class, () -> result.block());
        assertTrue(exception.getMessage().contains("Errore durante la chiamata all'API meteo"));
    }

    @Test
    void testGetWeatherByCityGeocodingParsingError() {
        String geocodingJson = "{\"results\":[{\"name\":\"Berlin\"}]}"; // missing lat/lon

        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(geocodingJson));

        Mono<WeatherData> result = weatherService.getWeatherByCity("Berlin");

        GeocodingApiException exception = assertThrows(GeocodingApiException.class, () -> result.block());
        assertTrue(exception.getMessage().contains("Errore nel parsing della risposta di geocoding"));
    }

    @Test
    void testGetWeatherByCityWeatherParsingError() {
        String geocodingJson = "{\"results\":[{\"latitude\":40.7128,\"longitude\":-74.0060,\"name\":\"New York\"}]}";
        String weatherJson = "{\"current_weather\":{\"weathercode\":0}}"; // missing temperature

        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(geocodingJson))
                .thenReturn(Mono.just(weatherJson));

        Mono<WeatherData> result = weatherService.getWeatherByCity("New York");

        WeatherApiException exception = assertThrows(WeatherApiException.class, () -> result.block());
        assertTrue(exception.getMessage().contains("Errore nel parsing della risposta meteo"));
    }

    @Test
    void testGetWeatherDescriptionSunny() {
        assertEquals("Cielo sereno", weatherService.getWeatherDescription(0));
    }

    @Test
    void testGetWeatherDescriptionThunderstorm() {
        assertEquals("Temporale", weatherService.getWeatherDescription(95));
    }

    @Test
    void testGetWeatherDescriptionUnknown() {
        assertEquals("Condizioni meteorologiche sconosciute", weatherService.getWeatherDescription(999));
    }

    @Test
    void testGetWeatherDescriptionHeavyRain() {
        assertEquals("Pioggia intensa", weatherService.getWeatherDescription(65));
    }

    @Test
    void testGetWeatherByCityEmptyInput() {
        // No need to mock webClient calls for this test as it fails early
        Mono<WeatherData> result = weatherService.getWeatherByCity("");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> result.block());
        assertEquals("Il nome della città non può essere vuoto.", exception.getMessage());
    }

    @Test
    void testHandleRateLimitExceeded() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.error(new org.springframework.web.reactive.function.client.WebClientResponseException(
                        429, "Too Many Requests", null, null, null)));

        Mono<WeatherData> result = weatherService.getWeatherByCity("Rome");

        GeocodingApiException exception = assertThrows(GeocodingApiException.class, () -> result.block());
        assertTrue(exception.getMessage().contains("Errore durante la chiamata all'API di geocoding"));
    }

    @Test
    void testHandleNetworkError() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.error(new java.net.ConnectException("Connection refused")));

        Mono<WeatherData> result = weatherService.getWeatherByCity("Rome");

        GeocodingApiException exception = assertThrows(GeocodingApiException.class, () -> result.block());
        assertTrue(exception.getMessage().contains("Errore durante la chiamata all'API di geocoding"));
    }
}
