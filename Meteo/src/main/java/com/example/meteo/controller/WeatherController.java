package com.example.meteo.controller;

import com.example.meteo.model.WeatherData;
import com.example.meteo.service.WeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping(value = "/weather", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<WeatherData> getWeather(@RequestParam String city) {
        return weatherService.getWeatherByCity(city);
    }


    @GetMapping("/forecast5")
    public Mono<String> getFiveDayForecast(@RequestParam String city) {
        return weatherService.getFiveDayForecast(city);
    }

    // /weather/multi now returns JSON (list of WeatherData). This replaces the previous textual output.
    @GetMapping("/weather/multi")
    public Mono<List<WeatherData>> getMultipleCitiesWeather(@RequestParam String cities) {
        return weatherService.getMultipleCitiesWeatherJson(cities);
    }
    
}
