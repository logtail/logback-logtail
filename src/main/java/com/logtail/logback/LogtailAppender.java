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

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Logback appender for sending logs to <a href="https://logtail.com">logtail.com</a>.
 *
 * @author tomas@logtail.com
 */
public class LogtailAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final String CUSTOM_USER_AGENT = "Logtail Logback Appender";

    private final Logger errorLog = LoggerFactory.getLogger(LogtailAppender.class);

    private final ObjectMapper dataMapper;

    private Client client;

    private boolean disabled;

    protected final MultivaluedMap<String, Object> headers;

    // Assignable fields

    protected PatternLayoutEncoder encoder;

    protected String appName;

    protected String ingestUrl = "https://in.logtail.com";

    protected List<String> mdcFields = new ArrayList<>();

    protected List<String> mdcTypes = new ArrayList<>();

    protected long connectTimeout = 5000;

    protected long readTimeout = 10000;

    private int batchSize = 1000;

    private List<ILoggingEvent> batch = new ArrayList<>();


    /**
     * Appender initialization.
     */
    public LogtailAppender() {
        this.headers = new MultivaluedHashMap<>();
        this.headers.add("User-Agent", CUSTOM_USER_AGENT);
        this.headers.add("Accept", MediaType.APPLICATION_JSON);
        this.headers.add("Content-Type", MediaType.APPLICATION_JSON);

        this.dataMapper = new ObjectMapper();
        this.dataMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.dataMapper.setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);
    }

    // Postpone client initialization to allow timeouts configuration
    protected Client client() {
        if (this.client == null) {
            this.client = ClientBuilder.newBuilder() //
                    .connectTimeout(this.connectTimeout, TimeUnit.MILLISECONDS) //
                    .readTimeout(this.readTimeout, TimeUnit.MILLISECONDS) //
                    .build();
        }

        return this.client;
    }

    /**
     * @see ch.qos.logback.core.UnsynchronizedAppenderBase#append(java.lang.Object)
     */
    @Override
    protected void append(ILoggingEvent event) {

        if (disabled) {
            return;
        }

        if (event.getLoggerName().equals(LogtailAppender.class.getName())) {
            return;
        }

        // Length 8+ means "Bearer " plus token, as a simple test
        if (!this.headers.containsKey("Authorization") || this.headers.getFirst("Authorization").toString().trim().length() < 8) {
            errorLog.warn("Empty ingest API key for Logtail ; disabling LogtailAppender");
            this.disabled = true;
            return;
        }

        appendToBatch(event);

    }

    private void appendToBatch(ILoggingEvent event) {
        batch.add(event);

        if (batch.size() >= batchSize) {
            flush();
        }
    }

    protected void flush() {
        try {
            String jsonData = convertLogEventsToJson(batch);

            Response response = callIngestApi(jsonData);

            if (response.getStatus() != 202) {
                LogtailResponse logtailResponse = convertResponseToObject(response);
                errorLog.error("Error calling Logtail : {} ({})", logtailResponse.getError(), response.getStatus());
            }

            batch.clear();
        } catch (JsonProcessingException e) {
            errorLog.error("Error processing JSON data : {}", e.getMessage());

        } catch (Exception e) {
            errorLog.error("Error trying to call Logtail : {}", e.getMessage());
        }
    }

    protected String convertLogEventsToJson(List<ILoggingEvent> events) throws JsonProcessingException {
        List<Map<String, Object>> values = events.stream()
                .map(this::buildPostData)
                .collect(Collectors.toList());
        return this.dataMapper.writeValueAsString(values);
    }

    protected LogtailResponse convertResponseToObject(Response response) throws JsonProcessingException {
        return new LogtailResponse(response.readEntity(String.class), response.getStatus());
    }

    /**
     * Call Logtail API posting given JSON formated string.
     *
     * @param jsonData
     *            a json oriented map
     * @return the http response
     */

    protected Response callIngestApi(String jsonData) {
        WebTarget wt = client().target(ingestUrl);

        return wt.request().headers(headers).post(Entity.json(jsonData));
    }

    /**
     * Converts a logback logging event to a JSON oriented array.
     *
     * @param event
     *            the logging event
     * @return a json oriented array
     */
    protected Map<String, Object> buildPostData(ILoggingEvent event) {
        Map<String, Object> line = new HashMap<>();

        line.put("dt", Long.toString(event.getTimeStamp()));
        line.put("level", event.getLevel().toString());
        line.put("app", this.appName);
        line.put("message", this.encoder != null ? new String(this.encoder.encode(event)) : event.getFormattedMessage());

        Map<String, Object> meta = new HashMap<>();
        meta.put("logger", event.getLoggerName());

        if (!mdcFields.isEmpty() && !event.getMDCPropertyMap().isEmpty()) {
            for (Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
                if (mdcFields.contains(entry.getKey())) {
                    String type = mdcTypes.get(mdcFields.indexOf(entry.getKey()));
                    meta.put(entry.getKey(), getMetaValue(type, entry.getValue()));
                }
            }
        }
        line.put("meta", meta);

        Map<String, Object> runtime = new HashMap<>();
        runtime.put("thread", event.getThreadName());

        if (event.hasCallerData()) {
            StackTraceElement[] callerData = event.getCallerData();

            if (callerData.length > 0) {
                StackTraceElement callerContext = callerData[0];

                runtime.put("class", callerContext.getClassName());
                runtime.put("method", callerContext.getMethodName());
                runtime.put("file", callerContext.getFileName());
                runtime.put("line", callerContext.getLineNumber());
            }
        }
        line.put("runtime", runtime);

        return line;
    }

    private Object getMetaValue(String type, String value) {
        try {
            if ("int".equals(type)) {
                return Integer.valueOf(value);
            }
            if ("long".equals(type)) {
                return Long.valueOf(value);
            }
            if ("boolean".equals(type)) {
                return Boolean.valueOf(value);
            }
        } catch (NumberFormatException e) {
            errorLog.warn("Error getting meta value : {}", e.getMessage());
        }
        return value;

    }

    public void setEncoder(PatternLayoutEncoder encoder) {
        this.encoder = encoder;
    }

    /**
     * Sets your Logtail ingest API key.
     *
     * @param ingestKey
     *            your ingest key
     */
    public void setIngestKey(String ingestKey) {
        this.headers.add("Authorization", String.format("Bearer %s", ingestKey));
    }

    /**
     * Sets the application name for Logtail indexation.
     *
     * @param appName
     *            application name
     */
    public void setAppName(String appName) {
        this.appName = appName;
    }

    /**
     * Sets the Logtail ingest API url.
     *
     * @param ingestUrl
     *            Logtail url
     */
    public void setIngestUrl(String ingestUrl) {
        this.ingestUrl = ingestUrl;
    }

    /**
     * Sets the MDC fields that needs to be sent inside Logtail metadata, separated by a comma.
     *
     * @param mdcFields
     *            MDC fields to use
     */
    public void setMdcFields(String mdcFields) {
        this.mdcFields = Arrays.asList(mdcFields.split(","));
    }

    /**
     * Sets the MDC fields types that will be sent inside Logtail metadata, in the same order as <i>mdcFields</i> are set
     * up, separated by a comma. Possible values are <i>string</i>, <i>boolean</i>, <i>int</i> and <i>long</i>.
     *
     * @param mdcTypes
     *            MDC fields types
     */
    public void setMdcTypes(String mdcTypes) {
        this.mdcTypes = Arrays.asList(mdcTypes.split(","));
    }

    /**
     * Sets the connection timeout of the underlying HTTP client, in milliseconds.
     *
     * @param connectTimeout
     *            client connection timeout
     */
    public void setConnectTimeout(Long connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * Sets the read timeout of the underlying HTTP client, in milliseconds.
     *
     * @param readTimeout
     *            client read timeout
     */
    public void setReadTimeout(Long readTimeout) {
        this.readTimeout = readTimeout;
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
     * Get the size of the message batch
     */
    public int getBatchSize() {
        return batchSize;
    }

    public boolean isDisabled() {
        return this.disabled;
    }

    @Override
    public void stop() {
        flush();
        super.stop();
    }
}
