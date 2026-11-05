package com.logtail.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.awt.Point;
import java.time.Instant;
import java.time.LocalDateTime;

public class App {
    public static void main(String[] args) {
        Logger logger = LoggerFactory.getLogger(App.class);

        MDC.put("requestId", "example-request-1");
        MDC.put("requestTime", Long.toString(Instant.now().getEpochSecond()));

        logger.info("Hello World!");

        logger.info("Point A", new Point(3, 4));
        logger.info("Point B", new Point(512, 768));
        logger.warn("Point C is outside the 1024 x 1024 map.", new Point(2048, 1536));

        logger.info("Snow White met a few dwarfs.", "Doc", "Sleepy", "Dopey", "Grumpy", "Happy", "Bashful", "Sneezy");

        try {
            throw new RuntimeException("An error occurred.", new Exception("The original cause."));
        } catch (RuntimeException exception) {
            logger.error("Logging a thrown exception.", exception);
        }

        logger.info("Logging local datetime serialized via JavaTimeModule.", LocalDateTime.of(2026, 1, 1, 12, 30));
    }
}
