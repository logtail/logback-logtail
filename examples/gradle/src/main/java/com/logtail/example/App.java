package com.logtail.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reproduces ConcurrentModificationException in LogtailAppender.
 *
 * The race condition occurs when:
 * 1. Multiple threads call logger.info() which adds to the batch Vector
 * 2. The flush thread calls batchToJson() which iterates over batch.subList()
 * 3. During iteration, another thread modifies the batch via add()
 */
public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    private static final AtomicInteger errorCount = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Starting ConcurrentModificationException reproduction test...");
        System.out.println("Running multiple iterations to trigger the race condition.");
        System.out.println();

        // Run multiple iterations to increase chance of hitting the race
        for (int iteration = 0; iteration < 5; iteration++) {
            System.out.println("=== Iteration " + (iteration + 1) + " ===");
            runTest();
            // Small pause between iterations
            TimeUnit.MILLISECONDS.sleep(500);
        }

        System.out.println();
        System.out.println("Test complete. If ConcurrentModificationException occurred,");
        System.out.println("you would see ERROR logs with 'Error clearing X logs from batch'");
        System.out.println("followed by 'Dropped batch of X logs.'");

        // Wait for final flushes
        System.out.println();
        System.out.println("Waiting 5 seconds for remaining flushes...");
        TimeUnit.SECONDS.sleep(5);
    }

    private static void runTest() throws InterruptedException {
        int threadCount = 1000;
        int logsPerThread = 50;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                MDC.put("requestId", UUID.randomUUID().toString());
                MDC.put("requestTime", Long.toString(Instant.now().getEpochSecond()));

                for (int i = 0; i < logsPerThread; i++) {
                    logger.info("Thread {} - Log message #{}", threadId, i);
                }

                MDC.clear();
            });
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        System.out.println("Iteration completed: " + (threadCount * logsPerThread) + " logs attempted");
    }
}
