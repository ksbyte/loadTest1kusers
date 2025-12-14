package BackendLoad;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class loadbackend {
    // ==== CONFIG (you can change safely) ====
    private static final String LOGIN_URL =
            "https://payrollapi.hostbooks.in/payroll/login";

    // backend API to test (change later if needed)
    private static final String BACKEND_API_URL =
            "https://payrollapi.hostbooks.in/attendance/list";

    private static final String TENANT_ID = "YOUR_TENANT_ID";
    private static final String X_LICENSE_KEY = "YOUR_X_LICENSE_KEY";

    // one valid test user (must be invited/active)
    private static final String LOGIN_EMAIL = "jaqigi@cyclelove.cc";
    private static final String LOGIN_PASSWORD = "Abc@12345";

    private static final int CONCURRENT_USERS = 100;
    // =======================================

    public static void main(String[] args) throws Exception {

        System.out.println("Backend load test starting...");
        System.out.println("Concurrent users: " + CONCURRENT_USERS);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // STEP 1: login once
        System.out.println("Logging in once to fetch token...");
        String token = loginOnce(client);
        System.out.println("Token received successfully.");

        // STEP 2: prepare concurrency controls
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_USERS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_USERS);

        long startTime = System.currentTimeMillis();

        // STEP 3: create worker threads
        for (int i = 0; i < CONCURRENT_USERS; i++) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(BACKEND_API_URL))
                            .header("Authorization", "Bearer " + token)
                            .header("Tenant-Id", TENANT_ID)
                            .header("X-License-Key", X_LICENSE_KEY)
                            .header("Accept", "application/json")
                            .GET()
                            .build();

                    HttpResponse<String> response =
                            client.send(request, HttpResponse.BodyHandlers.ofString());

                    System.out.println(
                            "Status: " + response.statusCode() +
                            " | Thread: " + Thread.currentThread().getName()
                    );

                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // wait until all threads are ready
        readyLatch.await();
        System.out.println("All threads ready. Firing requests together...");
        startLatch.countDown();

        // wait for completion
        doneLatch.await(2, TimeUnit.MINUTES);
        executor.shutdownNow();

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("Backend load test completed in " + totalTime + " ms");
    }

    // ===== helper: login once =====
    private static String loginOnce(HttpClient client) throws Exception {

        String payload = "{"
                + "\"email\":\"" + LOGIN_EMAIL + "\","
                + "\"password\":\"" + LOGIN_PASSWORD + "\""
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LOGIN_URL))
                .header("Content-Type", "application/json")
                .header("Tenant-Id", TENANT_ID)
                .header("X-License-Key", X_LICENSE_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Login failed: " + response.body());
        }

        // very simple token extraction (works for testing)
        String body = response.body();
        int start = body.indexOf("\"token\":\"") + 9;
        int end = body.indexOf("\"", start);

        return body.substring(start, end);
    }


}
