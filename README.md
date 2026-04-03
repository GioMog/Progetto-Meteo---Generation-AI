# Progetto-Meteo---Generation-AI
Progetto di app del meteo generato con l'AI seguendo il corso di AI di Generation

# Applicazione Meteo

Questa è una semplice applicazione Spring Boot che consente agli utenti di recuperare i dati meteorologici (temperatura e descrizione) per una città specificata utilizzando l'API Open-Meteo. L'applicazione ora include una gestione degli errori granulare, la configurazione degli URL delle API tramite file di properties e una cache in-memory dei risultati meteo per ridurre le chiamate esterne e migliorare le prestazioni.

## Panoramica del Progetto

L'applicazione è costruita con Spring Boot e utilizza `WebClient` per effettuare chiamate asincrone alle API di Open-Meteo. Il flusso di lavoro prevede:
1.  Un utente invia una richiesta con il nome di una città.
2.  L'applicazione utilizza l'API di Geocoding di Open-Meteo per ottenere le coordinate di latitudine e longitudine della città.
3.  Con le coordinate, l'applicazione chiama l'API di previsione di Open-Meteo per recuperare i dati meteorologici attuali.
4.  I dati meteorologici vengono elaborati e restituiti all'utente.
5.  La gestione degli errori è implementata in modo dettagliato per scenari come città non trovata, errori di parsing, problemi di rete o errori specifici delle API esterne.

## Cache dei dati meteo

Per migliorare le prestazioni e ridurre il numero di chiamate verso le API esterne, il servizio `WeatherService` ora incorpora una cache in-memory semplice:

- Implementazione: `ConcurrentHashMap` con voci che contengono il `WeatherData` e il timestamp di inserimento.
- TTL (time-to-live): le voci vengono considerate valide per un periodo configurabile (default 1 ora).
- Comportamento: al momento della richiesta la cache viene controllata (operazione non bloccante tramite `Mono.defer`). Se la voce è valida viene restituita immediatamente; altrimenti viene eseguita la chiamata esterna e il risultato valido viene salvato in cache prima di essere emesso.
- API di utilità: sono disponibili i metodi pubblici `clearCache()` e `putInCache(String city, WeatherData data)` per testare o pre-popolare la cache.

Configurazione della TTL nel file `application.properties` (valore in ore):

```properties
# numero di ore per cui la cache mantiene i risultati (default 1)
cache.ttl.hours=1
```

Nota: l'attuale implementazione evita lock globali e sfrutta strutture concorrenti; non impedisce richieste parallele duplicate per la stessa città se la voce è mancante. Per evitare duplicati si può introdurre un meccanismo di de-duplication delle richieste in-flight o utilizzare una libreria come Caffeine.

## Funzionalità

*   Recupera la temperatura attuale per una città specificata.
*   Fornisce una descrizione testuale delle condizioni meteorologiche basata sul codice meteorologico.
*   Fornisce umidità relativa, velocità del vento e precipitazioni quando disponibili.
*   Gestione degli errori dettagliata:
    *   `CityNotFoundException` per città non valide/non trovate
    *   `GeocodingApiException` per errori dell'API di geocoding o di parsing
    *   `WeatherApiException` per errori dell'API meteo o di parsing
    *   Gestione di input non valido (es. città vuota)
*   Utilizza l'API Open-Meteo per dati meteorologici affidabili.
*   Basato su Spring Boot per una facile configurazione e sviluppo.
*   URL delle API completamente configurabili tramite `application.properties`.
*   Cache in-memory dei risultati meteo per un periodo configurabile (vedi sopra).

## Installazione

Per installare ed eseguire l'applicazione localmente, segui questi passaggi:

### Prerequisiti

*   Java Development Kit (JDK) 17 o superiore
*   Apache Maven 3.x

### Passaggi

1.  **Clona il repository** (se applicabile, altrimenti salta questo passaggio):
    ```bash
    git clone <URL_DEL_TUO_REPOSITORY>
    cd meteo-app
    ```
2.  **Naviga nella directory del progetto**:
    ```bash
    cd C:\Users\g.moglia\Desktop\Meteo\Meteo
    ```
3.  **Configura gli URL delle API**:
    Apri `src/main/resources/application.properties` e assicurati che gli URL delle API siano configurati correttamente:
    ```properties
    api.geocoding.url=https://geocoding-api.open-meteo.com/v1/search
    api.weather.url=https://api.open-meteo.com/v1/forecast
    ```
    Puoi modificare questi URL per puntare a endpoint diversi o ambienti di test senza cambiare il codice.
4.  **Compila il progetto usando Maven**:
    ```bash
    mvn clean install
    ```
5.  **Esegui l'applicazione Spring Boot**:
    ```bash
    mvn spring-boot:run
    ```
    L'applicazione sarà avviata sulla porta `8080` per impostazione predefinita (configurabile in `src/main/resources/application.properties`).

## Guida all'Uso

L'applicazione espone un endpoint RESTful per recuperare i dati meteorologici.

### Endpoint

`GET /weather` (returns JSON)

### Nuova funzionalità: Previsioni a 5 giorni

La classe `WeatherService` espone ora un metodo che recupera una previsione meteo a 5 giorni usando l'API Open-Meteo e la restituisce come stringa semplice formattata. Puoi accedere a questa funzionalità tramite un endpoint dedicato o chiamando direttamente il servizio nel tuo codice.

Esempio di utilizzo sincrono (per test o demo):

```java
String forecast = weatherService.getFiveDayForecast("Rome").block();
System.out.println(forecast);
```

Esempio di output:

```
Previsioni per Rome
2026-04-03: 18.5°C / 10.2°C - Parzialmente nuvoloso
2026-04-04: 19.0°C / 11.0°C - Cielo sereno
2026-04-05: 17.8°C / 9.5°C - Pioggia leggera
2026-04-06: 16.2°C / 8.4°C - Nuvoloso
2026-04-07: 15.5°C / 7.9°C - Rovesci moderati
```

### Parametri di Query

*   `city`: Il nome della città per la quale si desiderano i dati meteorologici. (es. `Roma`, `London`, `New York`)

*   `cities`: Lista di città separata da virgola per richiedere il meteo attuale per molteplici città in una singola chiamata. (es. `Rome,London,Paris`)

### Esempio di Richiesta

Per ottenere i dati meteorologici per una città, apri il tuo browser o usa uno strumento come `curl` o Postman:

```
http://localhost:8080/weather?city=Roma
```

Per ottenere il meteo attuale per più città in formato JSON:

```
http://localhost:8080/weather/multi?cities=Rome,London,Paris
```

Esempio di output (formattazione testuale affiancata):

```
Rome   |London |Paris  
Rome   |London |Paris  
18.5°C |12.3°C |15.0°C 
45%    |60%    |55%    
3.5 m/s|5.1 m/s|2.8 m/s
0.0 mm |0.2 mm |1.1 mm 
Sunny  |Cloudy |Light rain
```

Suggerimento di visualizzazione chiara:

- Testuale affiancato (come sopra): utile per confronti rapidi fra città in terminale o output testuale.
- JSON strutturato: se usi il risultato in un'interfaccia web, preferisci un array di oggetti con campi {city, temperature, humidity, windSpeed, precipitation, description} per facilitare il rendering lato client.

Esempio JSON per una singola città:

```json
{
  "city": "Rome",
  "temperature": 18.5,
  "humidity": 45,
  "windSpeed": 3.5,
  "precipitation": 0.0,
  "description": "Sunny"
}
```

### Output di Esempio

Una richiesta riuscita restituisce un oggetto JSON simile al seguente:

```json
{
  "city": "Roma",
  "temperature": 25.5,
  "description": "Cielo sereno"
}
```

Se la città non viene trovata, riceverai un errore con un messaggio specifico:

```json
{
  "timestamp": "2026-03-24T10:30:00.000+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "message": "Città non trovata nella risposta di geocoding.",
  "path": "/weather"
}
```

Se si verifica un errore di parsing o di rete, il messaggio di errore sarà altrettanto dettagliato, facilitando la diagnosi.

## Miglioramenti Futuri

*   **Supporto per più parametri meteorologici**: Attualmente vengono recuperati solo temperatura e descrizione. Si potrebbero aggiungere velocità del vento, umidità, pressione, ecc.
*   **Cache dei dati meteorologici**: È già presente una cache in-memory semplice. Possibili evoluzioni:
    * Rimpiazzare con una soluzione pluggabile (`Cache<K,V>`), con implementazioni per InMemory (Caffeine), Persistente (SQLite) o Distribuita (Redis/Hazelcast).
    * Aggiungere limiti di dimensione, politiche di eviction (LRU/LFU) e metriche (hit/miss).
    * Implementare de-duplication delle richieste in-flight per evitare chiamate esterne duplicate su cache miss.
*   **Interfaccia Utente**: Sviluppare un'interfaccia utente web o mobile per un'interazione più user-friendly.
*   **Logging avanzato**: Implementare un sistema di logging più robusto per monitorare le richieste e le risposte.
*   **Internazionalizzazione**: Supportare più lingue per le descrizioni meteorologiche.
