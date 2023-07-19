package com.logtail.logback;

import java.io.IOException;

public class LogtailAppenderDecorator extends LogtailAppender {
    private Exception exception;
    private LogtailResponse response;
    protected int apiCalls = 0;

    @Override
    protected LogtailResponse callHttpURLConnection() throws IOException {
        try {
            apiCalls++;
            this.response = super.callHttpURLConnection();

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
