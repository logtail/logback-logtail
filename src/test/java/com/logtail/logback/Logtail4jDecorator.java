package com.logtail.logback;

import java.io.IOException;

public class Logtail4jDecorator extends Logtail4j {
    private Exception exception;
    private Logtail4jResponse response;
    protected int httpCalls;

    @Override
    protected Logtail4jResponse callHttpURLConnection() throws IOException {
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
        return this.response != null && this.response.getResponseMessage() != null;
    }

    public boolean isOK() {
        return this.response != null && this.response.getResponseCode() == 202;
    }

    public Throwable getException() {
        return exception;
    }

    public Logtail4jResponse getResponse() {
        return response;
    }
}
