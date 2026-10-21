package com.ridex.ridex_app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ridex.platform.web.ServiceUnavailableException;
import com.ridex.ridex_app.common.BadRequestException;
import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * The core's call to the pricing service, against a small fake server that can answer, fail or hang on demand. It shows what the
 * rider experiences in each case, without starting the real service.
 */
class HttpPricingClientTest {

    private static final String QUOTE_JSON = """
            {"category":"CAB","distanceKm":16.10,"durationMinutes":41,"baseFare":50.00,"distanceFare":225.40,"timeFare":82.00,
             "minimumFareApplied":false,"surgeMultiplier":1.00,"subtotal":357.40,"tax":17.87,"total":375.27,"currency":"INR",
             "aFieldAddedLater":"ignored"}""";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private volatile int status = 200;
    private final AtomicInteger failuresBeforeSuccess = new AtomicInteger();
    private volatile String body = QUOTE_JSON;
    private volatile long delayMs = 0;

    private HttpPricingClient client;

    private final LatLng from = new LatLng(new BigDecimal("12.971599"), new BigDecimal("77.594566"));
    private final LatLng to = new LatLng(new BigDecimal("13.0827"), new BigDecimal("77.5877"));

    @BeforeEach
    void startFakePricingService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/quotes", exchange -> {
            calls.incrementAndGet();
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean glitch = failuresBeforeSuccess.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
            byte[] bytes = (glitch ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(glitch ? 500 : status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // the client gave up (timeout): nothing to send to
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = new HttpPricingClient(new PricingProperties("http://localhost:" + server.getAddress().getPort(),
                "the-shared-test-token", 300, 400, 2, 20, 6, 4, 50f, 1500));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aQuoteIsReadAndExtraFieldsAreIgnored() {
        FareQuote quote = client.quote(from, to, VehicleCategory.CAB);

        assertThat(quote.total()).isEqualByComparingTo("375.27");
        assertThat(quote.distanceKm()).isEqualByComparingTo("16.10");
        assertThat(quote.currency()).isEqualTo("INR");
    }

    @Test
    void theServiceTokenIsSentSoThePricingServiceKnowsWhoIsCalling() {
        client.quote(from, to, VehicleCategory.CAB);

        assertThat(lastToken.get()).isEqualTo("the-shared-test-token");
    }

    @Test
    void aTripThePricingServiceRefusesBecomesAClearBadRequestAndIsNotRetried() {
        status = 400;
        body = "{\"status\":400,\"error\":\"BAD_REQUEST\",\"message\":\"Pickup and destination are too close together\"}";

        assertThatThrownBy(() -> client.quote(from, to, VehicleCategory.CAB))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Pickup and destination are too close together");
        assertThat(calls.get()).as("a correct refusal is not worth asking again").isEqualTo(1);
        assertThat(client.circuitBreaker().getMetrics().getNumberOfFailedCalls()).as("and it is not the service failing").isZero();
    }

    @Test
    void aFailingServiceIsRetriedThenReportedAsUnavailableWithRetryAfter() {
        status = 500;

        assertThatThrownBy(() -> client.quote(from, to, VehicleCategory.CAB))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
                    assertThat(e.getMessage()).contains("temporarily unavailable");
                    assertThat(e.getRetryAfterSeconds()).isPositive();
                });
        assertThat(calls.get()).as("one try and one retry").isEqualTo(2);
    }

    @Test
    void aGlitchThatPassesIsInvisibleToTheRider() {
        failuresBeforeSuccess.set(1);   // the first call fails, the retry succeeds

        FareQuote quote = client.quote(from, to, VehicleCategory.CAB);

        assertThat(quote.total()).isEqualByComparingTo("375.27");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aServiceThatHangsIsGivenUpOnInsteadOfHoldingTheRidersRequest() {
        delayMs = 1500;   // longer than the 400 ms read timeout
        long started = System.currentTimeMillis();

        assertThatThrownBy(() -> client.quote(from, to, VehicleCategory.CAB)).isInstanceOf(ServiceUnavailableException.class);

        assertThat(System.currentTimeMillis() - started).as("two tries of about 400 ms each, not 1.5 s x 2 and not for ever").isLessThan(2500);
    }

    @Test
    void repeatedFailuresOpenTheCircuitAndLaterRequestsFailFastWithoutCallingTheService() {
        status = 500;
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.quote(from, to, VehicleCategory.CAB)).isInstanceOf(ServiceUnavailableException.class);
        }
        assertThat(client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsWhenOpened = calls.get();

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> client.quote(from, to, VehicleCategory.CAB)).isInstanceOf(ServiceUnavailableException.class);

        assertThat(calls.get()).as("an open circuit does not even try").isEqualTo(callsWhenOpened);
        assertThat(System.currentTimeMillis() - started).as("and answers at once").isLessThan(200);
    }

    @Test
    void theCircuitClosesAgainWhenTheServiceRecovers() throws Exception {
        status = 500;
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.quote(from, to, VehicleCategory.CAB)).isInstanceOf(ServiceUnavailableException.class);
        }
        assertThat(client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        status = 200;
        Thread.sleep(1700);   // the open period (1.5 s) passes; the next call is a trial

        assertThat(client.quote(from, to, VehicleCategory.CAB).total()).isEqualByComparingTo("375.27");
    }

    @Test
    void wrongCredentialsAreAnOperatorProblemNotSomethingToBlameTheRiderFor() {
        status = 401;
        body = "{}";

        assertThatThrownBy(() -> client.quote(from, to, VehicleCategory.CAB)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(calls.get()).as("no point retrying a rejected password").isEqualTo(1);
    }
}
