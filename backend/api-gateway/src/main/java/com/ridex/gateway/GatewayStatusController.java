package com.ridex.gateway;

import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Which services are alive? The address list comes from configuration, which is our answer to "service discovery" for now:
 * see docs/gateway-concepts.md section on discovery. Admin only, because a map of the internal services helps an attacker.
 */
@RestController
public class GatewayStatusController {

    public record ServiceStatus(String name, String status, long millis) {
    }

    private final GatewayProperties properties;
    private final WebClient client = WebClient.create();

    public GatewayStatusController(GatewayProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/gateway/services")
    public Mono<List<ServiceStatus>> services(ServerWebExchange exchange) {
        // The JWT filter already proved who the caller is; whether that role may look is decided here
        if (!"ADMIN".equals(exchange.getAttribute("ridex.gateway.role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this resource");
        }
        return Flux.fromIterable(properties.services()).flatMap(this::probe).collectSortedList(
                java.util.Comparator.comparing(ServiceStatus::name));
    }

    private Mono<ServiceStatus> probe(GatewayProperties.Service service) {
        long started = System.nanoTime();
        return client.get().uri(service.healthUrl()).retrieve().toBodilessEntity()
                .timeout(Duration.ofSeconds(2))
                .map(response -> new ServiceStatus(service.name(), "UP", (System.nanoTime() - started) / 1_000_000))
                .onErrorResume(e -> Mono.just(new ServiceStatus(service.name(), "DOWN", (System.nanoTime() - started) / 1_000_000)));
    }
}
