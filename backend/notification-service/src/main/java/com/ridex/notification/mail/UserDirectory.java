package com.ridex.notification.mail;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Who is this user, and where do we write to them? This service owns notifications, not people, so it asks the core, which does.
 *
 * It is the ONE call this otherwise pure event consumer makes, and it is a lookup (it changes nothing). It is deliberately forgiving: short
 * timeouts, a ten-minute memory of each answer, and when the core cannot answer the email is simply skipped (the in-app notification was
 * already saved, so nothing is lost for the user).
 */
@Component
public class UserDirectory {

    public record Contact(String fullName, String email) {
    }

    private record Cached(Contact contact, Instant at) {
    }

    private static final Logger log = LoggerFactory.getLogger(UserDirectory.class);
    private static final Duration TTL = Duration.ofMinutes(10);

    private final RestClient client;
    private final String serviceToken;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    public UserDirectory(@Value("${ridex.core.base-url:http://localhost:8080}") String baseUrl,
                         @Value("${ridex.internal.service-token:}") String serviceToken) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.serviceToken = serviceToken;
    }

    public Optional<Contact> find(UUID userId) {
        if (userId == null || serviceToken.isBlank()) {
            return Optional.empty();
        }
        Cached hit = cache.get(userId);
        if (hit != null && hit.at().plus(TTL).isAfter(Instant.now())) {
            return Optional.of(hit.contact());
        }
        try {
            Contact contact = client.get().uri("/internal/users/{id}/contact", userId).header("X-Service-Token", serviceToken).retrieve().body(Contact.class);
            if (contact != null && contact.email() != null) {
                cache.put(userId, new Cached(contact, Instant.now()));
                return Optional.of(contact);
            }
        } catch (RuntimeException e) {
            log.warn("Could not look up a contact for an email: {}", e.getClass().getSimpleName());
        }
        return Optional.empty();
    }
}
