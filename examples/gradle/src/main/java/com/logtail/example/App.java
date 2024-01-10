package com.logtail.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.awt.Point;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import java.util.UUID;

public class App {
    public static void main(String[] args) {
        Logger logger = LoggerFactory.getLogger(App.class);

        MDC.put("requestId", UUID.randomUUID().toString());
        MDC.put("requestTime", Long.toString(Instant.now().getEpochSecond()));

        logger.info("Hello World!");

        Random coordinateGenerator = new Random();
        logger.info("Point A", new Point(coordinateGenerator.nextInt(1024), coordinateGenerator.nextInt(1024)));
        logger.info("Point B", new Point(coordinateGenerator.nextInt(1024), coordinateGenerator.nextInt(1024)));
        logger.info("Point C", new Point(coordinateGenerator.nextInt(1024), coordinateGenerator.nextInt(1024)));

        logger.info("Snow White met a few dwarfs.", "Doc", "Sleepy", "Dopey", "Grumpy", "Happy", "Bashful", "Sneezy");

        try {
            throw new RuntimeException("An error occurred.", new Exception("The original cause."));
        } catch (RuntimeException exception) {
            logger.error("Logging a thrown exception.", exception);
        }

        logger.info("Logging local datetime serialized via JavaTimeModule.", LocalDateTime.now());

        try {
            TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
