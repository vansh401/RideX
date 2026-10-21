import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * A small load generator, a single file you run with the JDK you already have (no download):
 *
 *   java -Djdk.httpclient.allowRestrictedHeaders=host scripts/loadtest/LoadTest.java \
 *        url=http://127.0.0.1:8088/api/fares/estimate host=ridex.localhost token=eyJ... method=POST body=@fare.json \
 *        concurrency=40 duration=60
 *
 * It sends requests from `concurrency` virtual threads in a closed loop (each thread sends the next request when the previous one is
 * answered), prints ONE LINE PER SECOND while it runs (so you can watch an outage or a scale-up happen), and at the end prints the
 * latency percentiles and WHICH PODS answered: the X-Gateway-Instance and X-Instance headers added by the gateway and the services.
 *
 * Arguments (key=value):  url (required)  method=GET|POST  body=<text or @file>  token=<access token>  host=<Host header>
 *                         concurrency=20  duration=30 (seconds)  rate=0 (max requests per second in total, 0 = as fast as possible)
 *                         timeout=5 (seconds)  quiet=false (no per-second lines)
 */
public class LoadTest {

    /** What happened in one second of the test. */
    static final class Bucket {
        final ConcurrentLinkedQueue<Long> latenciesMicros = new ConcurrentLinkedQueue<>();
        final LongAdder ok = new LongAdder();
        final LongAdder failed = new LongAdder();
        final Map<String, LongAdder> errors = new ConcurrentHashMap<>();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq > 0) {
                o.put(arg.substring(0, eq), arg.substring(eq + 1));
            }
        }
        String url = o.get("url");
        if (url == null) {
            System.err.println("usage: java LoadTest.java url=http://... [method=GET] [body=@file] [token=...] [host=...] [concurrency=20] [duration=30] [rate=0]");
            System.exit(2);
        }
        String method = o.getOrDefault("method", "GET");
        String body = o.get("body");
        if (body != null && body.startsWith("@")) {
            body = Files.readString(Path.of(body.substring(1)));
        }
        int concurrency = Integer.parseInt(o.getOrDefault("concurrency", "20"));
        int duration = Integer.parseInt(o.getOrDefault("duration", "30"));
        int rate = Integer.parseInt(o.getOrDefault("rate", "0"));
        int timeout = Integer.parseInt(o.getOrDefault("timeout", "5"));
        boolean quiet = Boolean.parseBoolean(o.getOrDefault("quiet", "false"));

        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(timeout)).build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout));
        if (o.get("token") != null) {
            builder.header("Authorization", "Bearer " + o.get("token"));
        }
        if (o.get("host") != null) {
            builder.header("Host", o.get("host"));
        }
        if (body != null) {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpRequest request = builder.build();

        Map<Integer, Bucket> buckets = new ConcurrentHashMap<>();
        Map<String, LongAdder> gatewayPods = new ConcurrentHashMap<>();
        Map<String, LongAdder> servicePods = new ConcurrentHashMap<>();
        long start = System.nanoTime();
        long end = start + duration * 1_000_000_000L;
        LongAdder sent = new LongAdder();

        ScheduledExecutorService printer = Executors.newSingleThreadScheduledExecutor();
        if (!quiet) {
            System.out.printf("%5s %7s %6s %6s %7s %7s %7s  %s%n", "t(s)", "req/s", "ok", "failed", "p50ms", "p95ms", "p99ms", "errors");
            int[] next = {0};
            printer.scheduleAtFixedRate(() -> {
                Bucket b = buckets.get(next[0]);
                if (b != null) {
                    print(next[0], b);
                    next[0]++;
                }
            }, 1100, 1000, TimeUnit.MILLISECONDS);
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                executor.submit(() -> {
                    while (System.nanoTime() < end) {
                        if (rate > 0) {
                            // crude pacing: do not run ahead of (elapsed seconds x rate) requests
                            while (sent.sum() > (System.nanoTime() - start) / 1_000_000_000.0 * rate + rate) {
                                Thread.sleep(5);
                            }
                        }
                        sent.increment();
                        long t0 = System.nanoTime();
                        int second = (int) ((t0 - start) / 1_000_000_000L);
                        Bucket bucket = buckets.computeIfAbsent(second, s -> new Bucket());
                        try {
                            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                            bucket.latenciesMicros.add((System.nanoTime() - t0) / 1000);
                            response.headers().firstValue("X-Gateway-Instance").ifPresent(v -> gatewayPods.computeIfAbsent(v, k -> new LongAdder()).increment());
                            response.headers().firstValue("X-Instance").ifPresent(v -> servicePods.computeIfAbsent(v, k -> new LongAdder()).increment());
                            if (response.statusCode() / 100 == 2) {
                                bucket.ok.increment();
                            } else {
                                bucket.failed.increment();
                                bucket.errors.computeIfAbsent("HTTP " + response.statusCode(), k -> new LongAdder()).increment();
                            }
                        } catch (java.net.http.HttpTimeoutException e) {
                            bucket.failed.increment();
                            bucket.errors.computeIfAbsent("timeout", k -> new LongAdder()).increment();
                        } catch (java.io.IOException e) {
                            bucket.failed.increment();
                            bucket.errors.computeIfAbsent("connection", k -> new LongAdder()).increment();
                        }
                    }
                    return null;
                });
            }
        }
        printer.shutdownNow();

        // ---- summary ----
        long ok = 0;
        long failed = 0;
        List<Long> all = new ArrayList<>();
        Map<String, Long> errorTotals = new TreeMap<>();
        for (Bucket b : buckets.values()) {
            ok += b.ok.sum();
            failed += b.failed.sum();
            all.addAll(b.latenciesMicros);
            b.errors.forEach((k, v) -> errorTotals.merge(k, v.sum(), Long::sum));
        }
        Collections.sort(all);
        long total = ok + failed;
        System.out.println();
        System.out.printf("requests: %d in %d s = %.0f req/s   ok: %d   failed: %d   success rate: %.2f%%%n", total, duration,
                total / (double) duration, ok, failed, total == 0 ? 0.0 : 100.0 * ok / total);
        System.out.printf("latency ms:  p50 %.1f   p95 %.1f   p99 %.1f   max %.1f%n", pct(all, 50), pct(all, 95), pct(all, 99), pct(all, 100));
        if (!errorTotals.isEmpty()) {
            System.out.println("errors: " + errorTotals);
        }
        distribution("gateway pods that answered (X-Gateway-Instance)", gatewayPods);
        distribution("service pods that answered   (X-Instance)", servicePods);
    }

    static void print(int second, Bucket b) {
        List<Long> l = new ArrayList<>(b.latenciesMicros);
        Collections.sort(l);
        long ok = b.ok.sum();
        long failed = b.failed.sum();
        Map<String, Long> errors = new TreeMap<>();
        b.errors.forEach((k, v) -> errors.put(k, v.sum()));
        System.out.printf("%5d %7d %6d %6d %7.1f %7.1f %7.1f  %s%n", second, ok + failed, ok, failed, pct(l, 50), pct(l, 95), pct(l, 99),
                errors.isEmpty() ? "" : errors);
    }

    static double pct(List<Long> sortedMicros, double p) {
        if (sortedMicros.isEmpty()) {
            return 0;
        }
        int index = (int) Math.min(sortedMicros.size() - 1, Math.max(0, Math.ceil(p / 100.0 * sortedMicros.size()) - 1));
        return sortedMicros.get(index) / 1000.0;
    }

    static void distribution(String title, Map<String, LongAdder> pods) {
        if (pods.isEmpty()) {
            return;
        }
        long sum = pods.values().stream().mapToLong(LongAdder::sum).sum();
        System.out.println(title + ":");
        pods.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            double share = 100.0 * e.getValue().sum() / sum;
            System.out.printf("  %-34s %7d  %5.1f%%  %s%n", e.getKey(), e.getValue().sum(), share, "#".repeat((int) Math.round(share / 2)));
        });
    }
}
