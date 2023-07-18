package com.logtail.logback;

import java.io.IOException;

public class Logtail4jDecorator extends Logtail4j {
    private Exception exception;
    private LogtailResponse response;
    protected int httpCalls;

    @Override
    protected LogtailResponse callHttpURLConnection() throws IOException {
        try {
            this.response = super.callHttpURLConnection();
            httpCalls++;
            return this.response;

        } catch (Exception e) {
            this.exception = e;
            throw e;
        }
    }

    public boolean hasException() {
        return this.exception != null;
    }

    public boolean hasError() {
        return this.response != null && this.response.getError() != null;
    }

    public boolean isOK() {
        return this.response != null && this.response.getStatus() == 202;
    }

    public Exception getException() {
        return exception;
    }

    public LogtailResponse getResponse() {
        return response;
    }
}
