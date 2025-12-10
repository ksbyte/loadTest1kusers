package SimultaneousLoad;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class loadTest1K {

    static class Credential {
        String email;
        String password;
        Credential(String u, String p){ email = u; password = p; }
    }

    static class Result {
        String email;
        int statusCode;
        boolean success;
        long durationMs;
        String snippet;
        String error;
        Result(String u, int code, boolean s, long d, String sn, String err){
            email = u; statusCode = code; success = s; durationMs = d; snippet = sn; error = err;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: java SimultaneousLoginLoadTest <users.csv> <threads|0=all> <tenantId> <xLicenseKey> [targetUrl] [timeoutSeconds]");
            System.out.println("Example: java SimultaneousLoginLoadTest users.csv 100 tenant-123 ABC-XYZ https://payrollapi.hostbooks.in/payroll/login 30");
            System.exit(1);
        }

        String csvPath = args[0];
        int threadLimit = Integer.parseInt(args[1]); // 0 means all rows in CSV
        String tenantId = args[2];
        String xLicenseKey = args[3];
        String targetUrl = args.length >= 5 ? args[4] : "https://payrollapi.hostbooks.in/payroll/login";
        int timeoutSeconds = args.length >= 6 ? Integer.parseInt(args[5]) : 30;

        List<Credential> creds = readCsv(csvPath);
        if (creds.isEmpty()) {
            System.err.println("No credentials found in " + csvPath);
            System.exit(2);
        }

        if (threadLimit <= 0 || threadLimit > creds.size()) threadLimit = creds.size();
        List<Credential> slice = creds.subList(0, threadLimit);

        System.out.printf("Loaded %d credential rows. Using %d threads. Target URL: %s%n", creds.size(), threadLimit, targetUrl);
        System.out.printf("TenantId: %s, X-License-Key: %s%n", tenantId, xLicenseKey);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(threadLimit);// cap thread pool reasonable
        CountDownLatch readyLatch = new CountDownLatch(threadLimit);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadLimit);

        List<Result> results = Collections.synchronizedList(new ArrayList<>());

        // Spawn worker tasks
        for (int i=0; i<threadLimit; i++) {
            final Credential c = slice.get(i);
            executor.submit(() -> {
                try {
                    // signal ready
                    readyLatch.countDown();
                    // wait for release
                    startLatch.await();

                    // build JSON body - adjust keys if your API differs
                    String json = "{\"email\":\"" + escapeJson(c.email) + "\",\"password\":\"" + escapeJson(c.password) + "\"}";

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(targetUrl))
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .header("Tenant-Id", tenantId)
                            .header("X-License-Key", xLicenseKey)
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build();

                    long start = System.nanoTime();
                    HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                    int sc = resp.statusCode();
                    boolean ok = (sc >= 200 && sc < 300) && resp.body() != null && resp.body().length() > 0;
                    String snippet = resp.body() == null ? "" : (resp.body().length() > 300 ? resp.body().substring(0, 300) : resp.body());
                    results.add(new Result(c.email, sc, ok, durationMs, snippet.replaceAll("[\\r\\n]", " "), null));
                } catch (Exception ex) {
                    long durationMs = 0;
                    results.add(new Result(c.email, -1, false, durationMs, "", ex.toString()));
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Wait until all threads are ready
        System.out.println("Waiting for all threads to be ready...");
        readyLatch.await(); // all worker tasks have started and are waiting on startLatch

        System.out.println("All threads ready. Releasing to fire requests at the same time (approx)...");
        Instant fireTime = Instant.now();
        startLatch.countDown(); // release all workers together

        // Wait for completion (optional timeout)
        boolean finished = doneLatch.await(120, TimeUnit.SECONDS);
        if (!finished) System.out.println("Warning: not all threads finished within timeout.");

        executor.shutdownNow();

        // write results to CSV
        String outFile = "results_" + System.currentTimeMillis() + ".csv";
        writeResultsCsv(outFile, results, fireTime.toString());
        System.out.println("Done. Results saved to " + outFile);
        summarize(results);
    }

    static String escapeJson(String s){
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }

    private static List<Credential> readCsv(String csvPath) throws IOException {
        List<Credential> list = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(csvPath), StandardCharsets.UTF_8)) {
            String header = br.readLine(); // assume header present
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                // naive CSV split - if your CSV contains commas in fields, replace with a CSV parser
                String[] parts = line.split(",", -1);
                if (parts.length < 2) continue;
                list.add(new Credential(parts[0].trim(), parts[1].trim()));
            }
        }
        return list;
    }

    private static void writeResultsCsv(String outFile, List<Result> results, String firedAt) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(outFile), StandardCharsets.UTF_8)) {
            bw.write("firedAt,email,statusCode,success,durationMs,snippet,error");
            bw.newLine();
            for (Result r : results) {
                bw.write(String.format("\"%s\",\"%s\",%d,%b,%d,\"%s\",\"%s\"",
                        firedAt,
                        r.email.replace("\"","\"\""),
                        r.statusCode,
                        r.success,
                        r.durationMs,
                        r.snippet.replace("\"","\"\""),
                        r.error == null ? "" : r.error.replace("\"","\"\"")));
                bw.newLine();
            }
        }
    }

    private static void summarize(List<Result> results) {
        long total = results.size();
        long success = results.stream().filter(r -> r.success).count();
        double avg = results.stream().mapToLong(r->r.durationMs).average().orElse(0.0);
        long p95 = percentile(results.stream().mapToLong(r->r.durationMs).toArray(), 95);
        long max = results.stream().mapToLong(r->r.durationMs).max().orElse(0L);
        System.out.printf("Total requests: %d, Success: %d (%.2f%%), Avg ms: %.1f, p95 ms: %d, max ms: %d%n",
                total, success, (total==0?0.0:100.0*success/total), avg, p95, max);
        long errors = results.stream().filter(r->!r.success).count();
        if (errors>0) {
            System.out.println("Sample errors:");
            results.stream().filter(r->!r.success).limit(10).forEach(r->System.out.println(" - " + r.email + " -> " + r.error + " code:" + r.statusCode));
        }
    }

    // simple percentile (works on small arrays)
    private static long percentile(long[] values, int p) {
        if (values.length==0) return 0;
        long[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int idx = (int)Math.ceil((p/100.0) * copy.length) - 1;
        idx = Math.max(0, Math.min(idx, copy.length-1));
        return copy[idx];
    }
}

