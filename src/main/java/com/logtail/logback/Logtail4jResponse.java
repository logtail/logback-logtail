package com.logtail.logback;

public class Logtail4jResponse {
    private final int responseCode;
    private final String responseMessage;

    public Logtail4jResponse(int responseCode, String responseMessage) {
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public String getResponseMessage() {
        return responseMessage;
    }
}
