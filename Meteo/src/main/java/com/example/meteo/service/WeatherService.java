package com.example.meteo.service;

import com.example.meteo.model.WeatherData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


@Service
public class WeatherService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // Simple in-memory cache: key = normalized city name, value = cached data + timestamp
    private final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();
    // Cache TTL (one hour)
    private final java.time.Duration cacheTtl = java.time.Duration.ofHours(1);

    @Value("${api.geocoding.url}")
    private String geocodingApiUrl;

    @Value("${api.weather.url}")
    private String weatherApiUrl;

    public WeatherService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Recupera i dati meteo per la città specificata.
     *
     * <p>Questo metodo esegue due chiamate HTTP in sequenza:
     * <ol>
     *   <li>Una chiamata all'API di geocoding per ottenere latitudine, longitudine e nome normalizzato della città.</li>
     *   <li>Una chiamata all'API meteo usando le coordinate ottenute per recuperare le informazioni di meteo corrente.</li>
     * </ol>
     * Il risultato è un {@code Mono<WeatherData>} che, se completato con successo, contiene la temperatura,
     * la descrizione meteo e il nome della città.</p>
     *
     * @param city il nome della città da cercare; non può essere {@code null} né una stringa vuota o composta solo da spazi.
     * @return {@code Mono<WeatherData>} un flusso reattivo che emette i dati meteo per la città richiesta.
     *         Il {@code Mono} può terminare con errore nei seguenti casi:
     *         <ul>
     *           <li>{@link IllegalArgumentException} - se il parametro {@code city} è {@code null} o vuoto;</li>
     *           <li>{@link GeocodingApiException} - in caso di errore nella chiamata o nel parsing della risposta dell'API di geocoding;</li>
     *           <li>{@link CityNotFoundException} - se l'API di geocoding non restituisce risultati per la città richiesta;</li>
     *           <li>{@link WeatherApiException} - in caso di errore nella chiamata o nel parsing della risposta dell'API meteo.</li>
     *         </ul>
     *
     * Esempi di utilizzo:
     * <pre>{@code
     * // Uso reattivo (non bloccante)
     * weatherService.getWeatherByCity("Rome")
     *     .subscribe(
     *         data -> System.out.println("Temperatura: " + data.getTemperature() + " °C - " + data.getDescription()),
     *         error -> System.err.println("Errore durante il recupero meteo: " + error.getMessage())
     *     );
     *
     * // Uso sincrono per test o demo (blocca il flusso)
     * WeatherData data = weatherService.getWeatherByCity("Rome").block();
     * if (data != null) {
     *     System.out.println("Città: " + data.getCity());
     *     System.out.println("Temperatura: " + data.getTemperature());
     *     System.out.println("Descrizione: " + data.getDescription());
     * }
     * }</pre>
     *
     * Note operative:
     * <ul>
     *   <li>Il metodo effettua chiamate esterne: considerare timeout, circuit breaker o retry a livello di {@code WebClient} o infrastruttura.</li>
     *   <li>Per i test unitari mockare il comportamento del {@code WebClient} (e/o dell'{@code ObjectMapper}) per simulare risposte di geocoding e meteo.</li>
     *   <li>Il metodo non lancia checked exceptions direttamente; gli errori sono propagati come segnali di errore sul {@code Mono} restituito.</li>
     * </ul>
     */
    public Mono<WeatherData> getWeatherByCity(String city) {
        // Use Mono.defer so cache check happens at subscription time and remains non-blocking
        return Mono.defer(() -> {
            if (city == null || city.trim().isEmpty()) {
                return Mono.error(new IllegalArgumentException("Il nome della città non può essere vuoto."));
            }

            String normalized = city.trim().toLowerCase();

            // Check cache
            CacheEntry cached = cache.get(normalized);
            if (cached != null) {
                java.time.Instant now = java.time.Instant.now();
                if (!cached.isExpired(now, cacheTtl)) {
                    return Mono.just(cached.getData());
                } else {
                    // Remove expired entry
                    cache.remove(normalized);
                }
            }

            String geocodingUrl = UriComponentsBuilder.fromUriString(geocodingApiUrl)
                    .queryParam("name", city)
                    .queryParam("count", 1)
                    .queryParam("language", "en")
                    .queryParam("format", "json")
                    .toUriString();

            Mono<WeatherData> liveCall = webClient.get()
                    .uri(geocodingUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorMap(e -> new GeocodingApiException("Errore durante la chiamata all'API di geocoding", e))
                    .flatMap(this::parseGeocodingResponse)
                    .flatMap(geocodingData -> {
                        // Request current weather plus hourly fields for humidity, wind and precipitation
                        String weatherUrl = UriComponentsBuilder.fromUriString(weatherApiUrl)
                                .queryParam("latitude", geocodingData.getLatitude())
                                .queryParam("longitude", geocodingData.getLongitude())
                                .queryParam("current_weather", true)
                                .queryParam("hourly", "relativehumidity_2m,precipitation,windspeed_10m")
                                .queryParam("timezone", "auto")
                                .toUriString();

                        return webClient.get()
                                .uri(weatherUrl)
                                .retrieve()
                                .bodyToMono(String.class)
                                .onErrorMap(e -> new WeatherApiException("Errore durante la chiamata all'API meteo", e))
                                .map(weatherResponse -> parseWeatherResponse(weatherResponse, geocodingData.getCityName()));
                    });

            // Store successful responses in cache before emitting
            return liveCall.doOnNext(data -> cache.put(normalized, new CacheEntry(data, java.time.Instant.now())));
        });
    }

    // Public helper to clear cache (useful for tests or manual invalidation)
    public void clearCache() {
        cache.clear();
    }

    // Public helper to pre-populate cache (useful for tests or offline scenarios)
    public void putInCache(String city, WeatherData data) {
        if (city == null || data == null) return;
        cache.put(city.trim().toLowerCase(), new CacheEntry(data, java.time.Instant.now()));
    }

    private Mono<GeocodingData> parseGeocodingResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.has("results") && !root.get("results").isEmpty()) {
                JsonNode result = root.get("results").get(0);
                double lat = result.get("latitude").asDouble();
                double lon = result.get("longitude").asDouble();
                String cityName = result.get("name").asText();
                return Mono.just(new GeocodingData(lat, lon, cityName));
            } else {
                return Mono.error(new CityNotFoundException("Città non trovata nella risposta di geocoding."));
            }
        } catch (Exception e) {
            return Mono.error(new GeocodingApiException("Errore nel parsing della risposta di geocoding", e));
        }
    }

    private WeatherData parseWeatherResponse(String weatherResponse, String cityName) {
        try {
            JsonNode weatherRoot = objectMapper.readTree(weatherResponse);
            JsonNode current = weatherRoot.get("current_weather");
            double temperature = current.get("temperature").asDouble();
            int weathercode = current.get("weathercode").asInt();
            String description = getWeatherDescription(weathercode);

            WeatherData data = new WeatherData();
            data.setCity(cityName);
            data.setTemperature(temperature);
            data.setDescription(description);

            // Try to extract hourly/latest humidity, windspeed and precipitation
            try {
                JsonNode hourly = weatherRoot.get("hourly");
                JsonNode timeArr = hourly != null ? hourly.get("time") : null;
                JsonNode humidityArr = hourly != null ? hourly.get("relativehumidity_2m") : null;
                JsonNode windArr = hourly != null ? hourly.get("windspeed_10m") : null;
                JsonNode precipArr = hourly != null ? hourly.get("precipitation") : null;

                if (timeArr != null && timeArr.size() > 0) {
                    // try to find the latest index matching current_weather.time
                    String currentTime = current.has("time") ? current.get("time").asText() : null;
                    int idx = -1;
                    if (currentTime != null) {
                        for (int i = 0; i < timeArr.size(); i++) {
                            if (currentTime.equals(timeArr.get(i).asText())) {
                                idx = i; break;
                            }
                        }
                    }
                    // fallback to first element if not found
                    if (idx == -1) idx = 0;

                    if (humidityArr != null && idx < humidityArr.size()) {
                        data.setHumidity(humidityArr.get(idx).asDouble());
                    }
                    if (windArr != null && idx < windArr.size()) {
                        data.setWindSpeed(windArr.get(idx).asDouble());
                    }
                    if (precipArr != null && idx < precipArr.size()) {
                        data.setPrecipitation(precipArr.get(idx).asDouble());
                    }
                }
            } catch (Exception ignore) {
                // Non critico: lasciamo i campi null se non disponibili
            }

            return data;
        } catch (Exception e) {
            throw new WeatherApiException("Errore nel parsing della risposta meteo", e);
        }
    }

    /**
     * Recupera una previsione meteo a 5 giorni per la città specificata e la restituisce
     * come una stringa formattata in modo semplice.
     * <p>
     * Questo metodo esegue le stesse operazioni di geocoding per ottenere le coordinate
     * e poi chiama l'API meteo richiedendo i dati giornalieri (max/min temperature e codice meteo)
     * per i prossimi 5 giorni.
     *</p>
     * @param city nome della città
     * @return Mono che emette una stringa formattata con la previsione a 5 giorni
     */
    public Mono<String> getFiveDayForecast(String city) {
        return Mono.defer(() -> {
            if (city == null || city.trim().isEmpty()) {
                return Mono.error(new IllegalArgumentException("Il nome della città non può essere vuoto."));
            }

            String normalized = city.trim().toLowerCase();

            String geocodingUrl = UriComponentsBuilder.fromUriString(geocodingApiUrl)
                    .queryParam("name", city)
                    .queryParam("count", 1)
                    .queryParam("language", "en")
                    .queryParam("format", "json")
                    .toUriString();

            return webClient.get()
                    .uri(geocodingUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorMap(e -> new GeocodingApiException("Errore durante la chiamata all'API di geocoding", e))
                    .flatMap(this::parseGeocodingResponse)
                    .flatMap(geocodingData -> {
                        String weatherUrl = UriComponentsBuilder.fromUriString(weatherApiUrl)
                                .queryParam("latitude", geocodingData.getLatitude())
                                .queryParam("longitude", geocodingData.getLongitude())
                                .queryParam("daily", "temperature_2m_max,temperature_2m_min,weathercode")
                                .queryParam("timezone", "auto")
                                .queryParam("forecast_days", 5)
                                .toUriString();

                        return webClient.get()
                                .uri(weatherUrl)
                                .retrieve()
                                .bodyToMono(String.class)
                                .onErrorMap(e -> new WeatherApiException("Errore durante la chiamata all'API meteo", e))
                                .map(weatherResponse -> parseFiveDayForecast(weatherResponse, geocodingData.getCityName()));
                    });
        });
    }

    /**
     * Recupera il meteo attuale per più città e lo restituisce in un formato "affiancato",
     * ossia con le città come colonne e righe separate per temperatura e descrizione.
     *
     * @param citiesCsv lista di città separata da virgola (es. "Rome,London,Paris")
     * @return Mono che emette una stringa formattata con i dati affiancati
     */
    // Removed textual multi-city formatter; use getMultipleCitiesWeatherJson(...) for JSON output.

    /**
     * Restituisce i dati meteo strutturati per più città come lista
     */
    public Mono<List<WeatherData>> getMultipleCitiesWeatherJson(String citiesCsv) {
        return Mono.defer(() -> {
            if (citiesCsv == null || citiesCsv.trim().isEmpty()) {
                return Mono.error(new IllegalArgumentException("Parametro 'cities' vuoto."));
            }

            List<String> cities = Arrays.stream(citiesCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            if (cities.isEmpty()) {
                return Mono.error(new IllegalArgumentException("Nessuna città valida fornita."));
            }

            return Flux.fromIterable(cities)
                    .flatMapSequential(city -> getWeatherByCity(city)
                            .onErrorResume(e -> {
                                WeatherData wd = new WeatherData();
                                wd.setCity(city);
                                wd.setTemperature(Double.NaN);
                                wd.setDescription("Errore: " + e.getMessage());
                                return Mono.just(wd);
                            })
                    )
                    .collectList();
        });
    }

    // formatSideBySide and padRight removed to avoid duplicate textual API; JSON endpoint is canonical.

    private String parseFiveDayForecast(String weatherResponse, String cityName) {
        try {
            JsonNode root = objectMapper.readTree(weatherResponse);
            JsonNode daily = root.get("daily");
            if (daily == null) {
                throw new WeatherApiException("Risposta meteo priva di sezione 'daily'");
            }

            JsonNode times = daily.get("time");
            JsonNode tMax = daily.get("temperature_2m_max");
            JsonNode tMin = daily.get("temperature_2m_min");
            JsonNode weathercodes = daily.get("weathercode");

            if (times == null || tMax == null || tMin == null || weathercodes == null) {
                throw new WeatherApiException("Dati giornalieri incompleti nella risposta meteo");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Previsioni per ").append(cityName).append("\n");

            int days = Math.min(5, times.size());
            for (int i = 0; i < days; i++) {
                String date = times.get(i).asText();
                double max = tMax.get(i).asDouble();
                double min = tMin.get(i).asDouble();
                int code = weathercodes.get(i).asInt();
                String desc = getWeatherDescription(code);
                sb.append(String.format("%s: %.1f°C / %.1f°C - %s", date, max, min, desc));
                if (i < days - 1) sb.append('\n');
            }

            return sb.toString();
        } catch (WeatherApiException e) {
            throw e;
        } catch (Exception e) {
            throw new WeatherApiException("Errore nel parsing della risposta meteo", e);
        }
    }

    public String getWeatherDescription(int weathercode) {
        switch (weathercode) {
            case 0: return "Cielo sereno";
            case 1: return "Prevalentemente sereno";
            case 2: return "Parzialmente nuvoloso";
            case 3: return "Nuvoloso";
            case 45: return "Nebbia";
            case 48: return "Nebbia con brina";
            case 51: return "Pioggerella leggera";
            case 53: return "Pioggerella moderata";
            case 55: return "Pioggerella intensa";
            case 56: return "Pioggerella ghiacciata leggera";
            case 57: return "Pioggerella ghiacciata intensa";
            case 61: return "Pioggia leggera";
            case 63: return "Pioggia moderata";
            case 65: return "Pioggia intensa";
            case 66: return "Pioggia ghiacciata leggera";
            case 67: return "Pioggia ghiacciata intensa";
            case 71: return "Neve leggera";
            case 73: return "Neve moderata";
            case 75: return "Neve intensa";
            case 77: return "Granuli di neve";
            case 80: return "Rovesci leggeri";
            case 81: return "Rovesci moderati";
            case 82: return "Rovesci violenti";
            case 85: return "Rovesci di neve leggeri";
            case 86: return "Rovesci di neve intensi";
            case 95: return "Temporale";
            case 96: return "Temporale con grandine leggera";
            case 99: return "Temporale con grandine intensa";
            default: return "Condizioni meteorologiche sconosciute";
        }
    }

    // Classe interna per contenere i dati di geocoding
    private static class GeocodingData {
        private final double latitude;
        private final double longitude;
        private final String cityName;

        public GeocodingData(double latitude, double longitude, String cityName) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.cityName = cityName;
        }

        public double getLatitude() {
            return latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        public String getCityName() {
            return cityName;
        }
    }

    // Internal cache entry
    private static class CacheEntry {
        private final WeatherData data;
        private final java.time.Instant timestamp;

        public CacheEntry(WeatherData data, java.time.Instant timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }

        public WeatherData getData() {
            return data;
        }

        public boolean isExpired(java.time.Instant now, java.time.Duration ttl) {
            return timestamp.plus(ttl).isBefore(now);
        }
    }

    // Eccezioni personalizzate
    public static class GeocodingApiException extends RuntimeException {
        public GeocodingApiException(String message) {
            super(message);
        }
        public GeocodingApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class WeatherApiException extends RuntimeException {
        public WeatherApiException(String message) {
            super(message);
        }
        public WeatherApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class CityNotFoundException extends RuntimeException {
        public CityNotFoundException(String message) {
            super(message);
        }
    }
}
