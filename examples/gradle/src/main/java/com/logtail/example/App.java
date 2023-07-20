package com.logtail.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

public class App {
    public static void main(String[] args) {
        Logger logger = LoggerFactory.getLogger(App.class);

        MDC.put("requestId", "stringValue");
        MDC.put("requestTime", Long.toString(Instant.now().getEpochSecond()));

        logger.info("hello world");

        try {
            TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
