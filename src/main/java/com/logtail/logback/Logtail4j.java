package com.logtail.logback;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Logtail4j extends UnsynchronizedAppenderBase<ILoggingEvent> {

    // Customizable variables
    protected String appName = "App";
    protected String ingestUrl = "https://in.logtail.com";
    protected String ingestKey = "";
    protected String userAgent = "Logtail4j";

    protected List<String> mdcFields = new ArrayList<>();
    protected List<String> mdcTypes = new ArrayList<>();

    protected int flushInterval = 3000;
    protected int connectionTimeout = 5000;
    protected int readTimeout = 10000;

    protected PatternLayoutEncoder patternLayoutEncoder;

    // Non-customizable variables
    protected Vector<ILoggingEvent> eventQueue;

    // Utils
    protected ScheduledExecutorService scheduledExecutorService;
    protected ObjectMapper objectMapper;
    protected Logger errorLogger;
    protected boolean disabled;

    // Re-located
    protected ThreadFactory threadFactory = r -> {
        Thread thread = Executors.defaultThreadFactory().newThread(r);
        thread.setName("logtail4j-appender");
        thread.setDaemon(true);
        return thread;
    };

    public Logtail4j() {

        this.errorLogger = LoggerFactory.getLogger(Logtail4j.class);

        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);

        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
        this.scheduledExecutorService.scheduleWithFixedDelay(new LogtailSender(), flushInterval, flushInterval, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void append(ILoggingEvent iLoggingEvent) {

        if (disabled)
            return;

        if (iLoggingEvent.getLoggerName().equals(Logtail4j.class.getName()))
            return;

        if (ingestUrl.isEmpty() || ingestKey.isEmpty())
            return;

        eventQueue.add(iLoggingEvent);
    }

    protected void processEventQueue() {
        if (eventQueue.isEmpty())
            return;

        try {
            HttpURLConnection connection = getHttpURLConnection(this.ingestUrl);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = eventQueueToJson().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            if (responseCode != 202) {
                errorLogger.error("Error calling Logtail : {} ({})", responseMessage, responseCode);
            }

            eventQueue.clear();

        } catch (JsonProcessingException e) {
            errorLogger.error("Error processing JSON data : {}", e.getMessage());

        } catch (Exception e) {
            errorLogger.error("Error trying to call Logtail : {}", e.getMessage());
        }
    }

    protected String eventQueueToJson() throws JsonProcessingException {
        return this.objectMapper.writeValueAsString(eventQueue.stream()
                .map(this::buildPostData)
                .collect(Collectors.toList()));
    }

    protected Map<String, Object> buildPostData(ILoggingEvent iLoggingEvent) {
        Map<String, Object> logLine = new HashMap<>();
        logLine.put("dt", Long.toString(iLoggingEvent.getTimeStamp()));
        logLine.put("level", iLoggingEvent.getLevel().toString());
        logLine.put("app", this.appName);
        logLine.put("message", generateLogMessage(iLoggingEvent));
        logLine.put("meta", generateLogMeta(iLoggingEvent));
        logLine.put("runtime", generateLogRuntime(iLoggingEvent));
        return logLine;
    }

    protected String generateLogMessage(ILoggingEvent iLoggingEvent) {
        return this.patternLayoutEncoder != null ? new String(this.patternLayoutEncoder.encode(iLoggingEvent))
                : iLoggingEvent.getFormattedMessage();
    }

    protected Map<String, Object> generateLogMeta(ILoggingEvent iLoggingEvent) {
        Map<String, Object> logMeta = new HashMap<>();
        logMeta.put("logger", iLoggingEvent.getLoggerName());

        if (!mdcFields.isEmpty() && !iLoggingEvent.getMDCPropertyMap().isEmpty()) {
            for (Entry<String, String> entry : iLoggingEvent.getMDCPropertyMap().entrySet()) {
                if (mdcFields.contains(entry.getKey())) {
                    String type = mdcTypes.get(mdcFields.indexOf(entry.getKey()));
                    logMeta.put(entry.getKey(), getMetaValue(type, entry.getValue()));
                }
            }
        }

        return logMeta;
    }

    protected Map<String, Object> generateLogRuntime(ILoggingEvent iLoggingEvent) {
        Map<String, Object> logRuntime = new HashMap<>();
        logRuntime.put("thread", iLoggingEvent.getThreadName());

        if (iLoggingEvent.hasCallerData()) {
            StackTraceElement[] callerData = iLoggingEvent.getCallerData();

            if (callerData.length > 0) {
                StackTraceElement callerContext = callerData[0];

                logRuntime.put("class", callerContext.getClassName());
                logRuntime.put("method", callerContext.getMethodName());
                logRuntime.put("file", callerContext.getFileName());
                logRuntime.put("line", callerContext.getLineNumber());
            }
        }

        return logRuntime;
    }

    protected Object getMetaValue(String type, String value) {
        try {
            switch (type) {
                case "int":
                    return Integer.valueOf(value);
                case "long":
                    return Long.valueOf(value);
                case "boolean":
                    return Boolean.valueOf(value);
            }
        } catch (NumberFormatException e) {
            errorLogger.error("Error getting meta value - {}", e.getMessage());
        }

        return value;
    }

    protected HttpURLConnection getHttpURLConnection(String ingestUrl) throws IOException {
        HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(ingestUrl).openConnection();
        httpURLConnection.setDoOutput(true);
        httpURLConnection.setDoInput(true);
        httpURLConnection.setRequestProperty("User-Agent", this.userAgent);
        httpURLConnection.setRequestProperty("Accept", "application/json");
        httpURLConnection.setRequestProperty("Content-Type", "application/json");
        httpURLConnection.setRequestProperty("Charset", "UTF-8");
        httpURLConnection.setRequestProperty("Authorization", String.format("Bearer %s", this.ingestKey));
        httpURLConnection.setRequestMethod("POST");
        httpURLConnection.setConnectTimeout(this.connectionTimeout);
        httpURLConnection.setReadTimeout(this.readTimeout);
        return httpURLConnection;
    }

    public class LogtailSender implements Runnable {
        @Override
        public void run() {
            try {
                processEventQueue();
            } catch (Exception e) {
                errorLogger.error(e.getMessage());
            }
        }
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public void setIngestUrl(String ingestUrl) {
        this.ingestUrl = ingestUrl;
    }

    public void setIngestKey(String ingestKey) {
        this.ingestKey = ingestKey;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public void setMdcFields(String mdcFields) {
        this.mdcFields = Arrays.asList(mdcFields.split(","));
    }

    public void setMdcTypes(String mdcTypes) {
        this.mdcTypes = Arrays.asList(mdcTypes.split(","));
    }

    public void setFlushInterval(int flushInterval) {
        this.flushInterval = flushInterval;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public void setPatternLayoutEncoder(PatternLayoutEncoder patternLayoutEncoder) {
        this.patternLayoutEncoder = patternLayoutEncoder;
    }

    @Override
    public void stop() {
        processEventQueue();
        super.stop();
    }
}
