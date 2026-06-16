package io.github.rrobetti.adaptivebulkhead.examples;

import io.github.rrobetti.adaptivebulkhead.AdaptiveBulkhead;
import io.github.rrobetti.adaptivebulkhead.Permit;
import io.github.rrobetti.adaptivebulkhead.Priority;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;

public final class AdaptiveBulkheadExamples {
    private AdaptiveBulkheadExamples() {
    }

    public static void main(String[] args) throws Exception {
        try (AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                .maxConcurrency(100)
                .child("payments", lane -> lane
                        .guaranteedConcurrency(40)
                        .maxConcurrency(80)
                        .maximumBorrow(40)
                        .minimumRetainedCapacity(40)
                        .priority(Priority.CRITICAL)
                        .weight(10))
                .child("api", lane -> lane
                        .guaranteedConcurrency(30)
                        .maxConcurrency(60)
                        .maximumBorrow(30)
                        .minimumRetainedCapacity(15)
                        .priority(Priority.NORMAL)
                        .weight(5))
                .child("reports", lane -> lane
                        .guaranteedConcurrency(0)
                        .maxConcurrency(30)
                        .minimumRetainedCapacity(0)
                        .priority(Priority.BACKGROUND)
                        .weight(1))
                .build()) {
            try (Permit permit = bulkhead.acquireOrThrow("payments")) {
                System.out.println("processPayment");
            }

            try (var platformExecutor = Executors.newFixedThreadPool(8)) {
                AdaptiveBulkhead platformBulkhead = bulkhead.withExecutor(platformExecutor);
                System.out.println(platformBulkhead.submit("payments", () -> "payment-ok").get());
            }

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.com")).GET().build();
            bulkhead.submitAsync("api", () -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                    .thenAccept(response -> System.out.println(response.statusCode()))
                    .toCompletableFuture()
                    .join();
        }
    }
}
