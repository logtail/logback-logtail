package com.logtail.logback;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class LogtailAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    // Customizable variables
    protected String appName;
    protected String ingestUrl = "https://in.logs.betterstack.com";
    protected String sourceToken;
    protected String userAgent = "Better Stack Logback Appender";

    protected List<String> mdcFields = new ArrayList<>();
    protected List<String> mdcTypes = new ArrayList<>();

    protected int maxQueueSize = 100000;
    protected int batchSize = 1000;
    protected int batchInterval = 3000;
    protected int connectTimeout = 5000;
    protected int readTimeout = 10000;
    protected int maxRetries = 5;
    protected int retrySleepMilliseconds = 300;

    protected PatternLayoutEncoder encoder;

    // Non-customizable variables
    protected Vector<ILoggingEvent> batch = new Vector<>();
    protected AtomicBoolean isFlushing = new AtomicBoolean(false);
    protected boolean mustReflush = false;
    protected boolean warnAboutMaxQueueSize = true;

    // Utils
    protected ScheduledExecutorService scheduledExecutorService;
    protected ScheduledFuture<?> scheduledFuture;
    protected ObjectMapper dataMapper;
    protected Logger logger;
    protected int retrySize = 0;
    protected int retries = 0;
    protected boolean disabled = false;

    protected ThreadFactory threadFactory = r -> {
        Thread thread = Executors.defaultThreadFactory().newThread(r);
        thread.setName("logtail-appender");
        thread.setDaemon(true);
        return thread;
    };

    public LogtailAppender() {
        logger = LoggerFactory.getLogger(LogtailAppender.class);

        dataMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
        scheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(new LogtailSender(), batchInterval, batchInterval, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (disabled)
            return;

        if (event.getLoggerName().equals(LogtailAppender.class.getName()))
            return;

        if (this.ingestUrl.isEmpty() || this.sourceToken == null || this.sourceToken.isEmpty()) {
            // Prevent potential dead-lock, when a blocking logger is configured - avoid using logger directly in append
            startThread("logtail-warning-logger", () -> {
                logger.warn("Missing Source token for Better Stack - disabling LogtailAppender. Find out how to fix this at: https://betterstack.com/docs/logs/java ");
            });
            this.disabled = true;
            return;
        }

        if (batch.size() < maxQueueSize) {
            batch.add(event);
        }

        if (warnAboutMaxQueueSize && batch.size() == maxQueueSize) {
            this.warnAboutMaxQueueSize = false;
            // Prevent potential dead-lock, when a blocking logger is configured - avoid using logger directly in append
            startThread("logtail-error-logger", () -> {
                logger.error("Maximum number of messages in queue reached ({}). New messages will be dropped.", maxQueueSize);
            });
        }

        if (batch.size() >= batchSize) {
            if (isFlushing.get())
                return;

            startThread("logtail-appender-flush", new LogtailSender());
        }
    }

    protected void startThread(String threadName, Runnable runnable) {
        Thread thread = Executors.defaultThreadFactory().newThread(runnable);
        thread.setName(threadName);
        thread.start();
    }

    protected void flush() {
        if (batch.isEmpty())
            return;

        // Guaranteed to not be running concurrently
        if (isFlushing.getAndSet(true))
            return;

        mustReflush = false;

        int flushedSize = batch.size();
        if (flushedSize > batchSize) {
            flushedSize = batchSize;
            mustReflush = true;
        }
        if (retries > 0 && flushedSize > retrySize) {
            flushedSize = retrySize;
            mustReflush = true;
        }

        if (!flushLogs(flushedSize)) {
            mustReflush = true;
        }

        isFlushing.set(false);

        if (mustReflush || batch.size() >= batchSize)
            flush();
    }

    protected boolean flushLogs(int flushedSize) {
        retrySize = flushedSize;

        try {
            if (retries > maxRetries) {
                synchronized (batch) {
                    batch.subList(0, flushedSize).clear();
                }
                logger.error("Dropped batch of {} logs.", flushedSize);
                warnAboutMaxQueueSize = true;
                retries = 0;

                return true;
            }

            if (retries > 0) {
                logger.info("Retrying to send {} logs to Better Stack ({} / {})", flushedSize, retries, maxRetries);
                try {
                    TimeUnit.MILLISECONDS.sleep(retrySleepMilliseconds);
                } catch (InterruptedException e) {
                    // Continue
                }
            }

            LogtailResponse response = callHttpURLConnection(flushedSize);

            if (response.getStatus() >= 300 || response.getStatus() < 200) {
                logger.error("Error calling Better Stack : {} ({})", response.getError(), response.getStatus());
                retries++;

                return false;
            }

            synchronized (batch) {
                batch.subList(0, flushedSize).clear();
            }
            warnAboutMaxQueueSize = true;
            retries = 0;

            return true;

        } catch (ConcurrentModificationException e) {
            logger.error("Error clearing {} logs from batch, will retry immediately.", flushedSize, e);
            retries = maxRetries; // No point in retrying to send the data

        } catch (JsonProcessingException e) {
            logger.error("Error processing JSON data : {}", e.getMessage(), e);
            retries = maxRetries; // No point in retrying when batch cannot be processed into JSON

        } catch (Exception e) {
            logger.error("Error trying to call Better Stack : {}", e.getMessage(), e);
        }

        retries++;

        return false;
    }

    protected LogtailResponse callHttpURLConnection(int flushedSize) throws IOException {
        HttpURLConnection connection = getHttpURLConnection();

        try {
            connection.connect();
        } catch (Exception e) {
            logger.error("Error trying to call Better Stack : {}", e.getMessage(), e);
        }

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = batchToJson(flushedSize).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
        }

        connection.disconnect();

        return new LogtailResponse(connection.getResponseMessage(), connection.getResponseCode());
    }

    protected HttpURLConnection getHttpURLConnection() throws IOException {
        HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(this.ingestUrl).openConnection();
        httpURLConnection.setDoOutput(true);
        httpURLConnection.setDoInput(true);
        httpURLConnection.setRequestProperty("User-Agent", this.userAgent);
        httpURLConnection.setRequestProperty("Accept", "application/json");
        httpURLConnection.setRequestProperty("Content-Type", "application/json");
        httpURLConnection.setRequestProperty("Charset", "UTF-8");
        httpURLConnection.setRequestProperty("Authorization", String.format("Bearer %s", this.sourceToken));
        httpURLConnection.setRequestMethod("POST");
        httpURLConnection.setConnectTimeout(this.connectTimeout);
        httpURLConnection.setReadTimeout(this.readTimeout);
        return httpURLConnection;
    }

    protected String batchToJson(int flushedSize) throws JsonProcessingException {
        List<ILoggingEvent> snapshot;
        synchronized (batch) {
            snapshot = new ArrayList<>(batch.subList(0, flushedSize));
        }
        return this.dataMapper.writeValueAsString(
            snapshot.stream()
                .map(this::buildPostData)
                .collect(Collectors.toList())
        );
    }

    protected Map<String, Object> buildPostData(ILoggingEvent event) {
        Map<String, Object> logLine = new HashMap<>();
        logLine.put("dt", Long.toString(event.getTimeStamp()));
        logLine.put("level", event.getLevel().toString());
        logLine.put("app", this.appName);
        logLine.put("message", generateLogMessage(event));
        logLine.put("meta", generateLogMeta(event));
        logLine.put("runtime", generateLogRuntime(event));
        logLine.put("args", event.getArgumentArray());
        if (event.getThrowableProxy() != null) {
            logLine.put("throwable", generateLogThrowable(event.getThrowableProxy()));
        }

        return logLine;
    }

    protected String generateLogMessage(ILoggingEvent event) {
        return this.encoder != null ? new String(this.encoder.encode(event)) : event.getFormattedMessage();
    }

    protected Map<String, Object> generateLogMeta(ILoggingEvent event) {
        Map<String, Object> logMeta = new HashMap<>();
        logMeta.put("logger", event.getLoggerName());

        if (!mdcFields.isEmpty() && !event.getMDCPropertyMap().isEmpty()) {
            for (Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
                if (mdcFields.contains(entry.getKey())) {
                    String type = mdcTypes.get(mdcFields.indexOf(entry.getKey()));
                    logMeta.put(entry.getKey(), getMetaValue(type, entry.getValue()));
                }
            }
        }

        return logMeta;
    }

    protected Map<String, Object> generateLogRuntime(ILoggingEvent event) {
        Map<String, Object> logRuntime = new HashMap<>();
        logRuntime.put("thread", event.getThreadName());

        if (event.hasCallerData()) {
            StackTraceElement[] callerData = event.getCallerData();

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

    protected Map<String, Object> generateLogThrowable(IThrowableProxy throwable) {
        Map<String, Object> logThrowable = new HashMap<>();
        logThrowable.put("message", throwable.getMessage());
        logThrowable.put("class", throwable.getClassName());
        logThrowable.put("stackTrace", throwable.getStackTraceElementProxyArray());
        if (throwable.getCause() != null) {
            logThrowable.put("cause", generateLogThrowable(throwable.getCause()));
        }

        return logThrowable;
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
            logger.error("Error getting meta value - {}", e.getMessage(), e);
        }

        return value;
    }

    public class LogtailSender implements Runnable {
        @Override
        public void run() {
            try {
                flush();
            } catch (Exception e) {
                logger.error("Error trying to flush : {}", e.getMessage(), e);
                if (isFlushing.get()) {
                    isFlushing.set(false);
                }
            }
        }
    }

    /**
     * Sets the application name for Better Stack indexation.
     *
     * @param appName
     *            application name
     */
    public void setAppName(String appName) {
        this.appName = appName;
    }

    /**
     * Sets the Better Stack ingest API url.
     *
     * @param ingestUrl
     *            Better Stack ingest url
     */
    public void setIngestUrl(String ingestUrl) {
        this.ingestUrl = ingestUrl;
    }

    /**
     * Sets your Better Stack source token.
     *
     * @param sourceToken
     *            your Better Stack source token
     */
    public void setSourceToken(String sourceToken) {
        this.sourceToken = sourceToken;
    }

    /**
     * Deprecated! Kept for backward compatibility.
     * Sets your Better Stack source token if unset.
     *
     * @param ingestKey
     *            your Better Stack source token
     */
    public void setIngestKey(String ingestKey) {
        if (this.sourceToken == null) {
            return;
        }
        this.sourceToken = ingestKey;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    /**
     * Sets the MDC fields that will be sent as metadata, separated by a comma.
     *
     * @param mdcFields
     *            MDC fields to include in structured logs
     */
    public void setMdcFields(String mdcFields) {
        this.mdcFields = Arrays.asList(mdcFields.split(","));
    }

    /**
     * Sets the MDC fields types that will be sent as metadata, in the same order as <i>mdcFields</i> are set
     * up, separated by a comma. Possible values are <i>string</i>, <i>boolean</i>, <i>int</i> and <i>long</i>.
     *
     * @param mdcTypes
     *            MDC fields types
     */
    public void setMdcTypes(String mdcTypes) {
        this.mdcTypes = Arrays.asList(mdcTypes.split(","));
    }

    /**
     * Sets the maximum number of messages in the queue. Messages over the limit will be dropped.
     *
     * @param maxQueueSize
     *            max size of the message queue
     */
    public void setMaxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
    }

    /**
     * Sets the batch size for the number of messages to be sent via the API
     *
     * @param batchSize
     *            size of the message batch
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /**
     * Get the batch size for the number of messages to be sent via the API
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the maximum wait time for a batch to be sent via the API, in milliseconds.
     *
     * @param batchInterval
     *            maximum wait time for message batch [ms]
     */
    public void setBatchInterval(int batchInterval) {
        scheduledFuture.cancel(false);
        scheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(new LogtailSender(), batchInterval, batchInterval, TimeUnit.MILLISECONDS);

        this.batchInterval = batchInterval;
    }

    /**
     * Sets the connection timeout of the underlying HTTP client, in milliseconds.
     *
     * @param connectTimeout
     *            client connection timeout [ms]
     */
    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * Sets the read timeout of the underlying HTTP client, in milliseconds.
     *
     * @param readTimeout
     *            client read timeout
     */
    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * Sets the maximum number of retries for sending logs to Better Stack. After that, current batch of logs will be dropped.
     *
     * @param maxRetries
     *            max number of retries for sending logs
     */
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /**
     * Sets the number of milliseconds to sleep before retrying to send logs to Better Stack.
     *
     * @param retrySleepMilliseconds
     *            number of milliseconds to sleep before retry
     */
    public void setRetrySleepMilliseconds(int retrySleepMilliseconds) {
        this.retrySleepMilliseconds = retrySleepMilliseconds;
    }

    /**
     * Registers a dynamically loaded Module object to ObjectMapper used for serialization of logged data.
     *
     * @param className
     *            fully qualified class name of the module, eg. "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule"
     */
    public void setObjectMapperModule(String className) {
        try {
            Module module = (Module) Class.forName(className).newInstance();
            dataMapper.registerModule(module);
            logger.info("Module '{}' successfully registered in ObjectMapper.", className);
        } catch (ClassNotFoundException|InstantiationException|IllegalAccessException e) {
            logger.error("Module '{}' couldn't be registered in ObjectMapper : ", className, e);
        }
    }

    public void setEncoder(PatternLayoutEncoder encoder) {
        this.encoder = encoder;
    }

    public boolean isDisabled() {
        return this.disabled;
    }

    @Override
    public void stop() {
        scheduledExecutorService.shutdown();
        mustReflush = true;
        flush();
        super.stop();
    }
}
