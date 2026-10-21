package com.ridex.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.jayway.jsonpath.JsonPath;

/** A tiny HTTP client for the tests: real requests to the running service. */
final class Http {

    record Response(int status, String body) {
        <T> T json(String path) {
            return JsonPath.read(body, path);
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private final int port;

    Http(int port) {
        this.port = port;
    }

    Response get(String path, String token) {
        return send(builder(path, token).GET());
    }

    Response post(String path, String token, String json) {
        return send(builder(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json)));
    }

    private HttpRequest.Builder builder(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
