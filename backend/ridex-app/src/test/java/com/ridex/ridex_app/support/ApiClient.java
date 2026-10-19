package com.ridex.ridex_app.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

/** A tiny HTTP client for integration tests: real requests to the running application. */
public class ApiClient {

    public record Response(int status, String body) {

        /** Reads a value from the JSON body with a JsonPath such as "$.fare.total". */
        public <T> T json(String path) {
            return JsonPath.read(body, path);
        }

        public String id() {
            return json("$.id");
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;

    public ApiClient(int port) {
        this.port = port;
    }

    public Response get(String path, String token) {
        return send(builder(path, token).GET());
    }

    public Response post(String path, String token, String json) {
        return send(builder(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json)));
    }

    public Response put(String path, String token, String json) {
        return send(builder(path, token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    public Response delete(String path, String token) {
        return send(builder(path, token).DELETE());
    }

    /** Logs in through the real endpoint and returns the access token. */
    public String login(String email, String password) {
        Response response = post("/api/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        if (response.status() != 200) {
            throw new IllegalStateException("Login failed: " + response.status() + " " + response.body());
        }
        return response.json("$.accessToken");
    }

    /** multipart/form-data with one text field ("type") and one file ("file"). */
    public Response upload(String path, String token, String type, String filename, byte[] content) {
        String boundary = "----ridex" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"type\"\r\n\r\n" + type + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return send(builder(path, token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    public byte[] download(String path, String token) {
        try {
            HttpResponse<byte[]> response = http.send(builder(path, token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Download failed: " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
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
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
