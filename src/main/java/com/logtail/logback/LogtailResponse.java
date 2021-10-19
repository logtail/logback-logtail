package com.logtail.logback;

/**
 * Holder for Logtail error message.
 * 
 * @author tomas@logtail.com
 */
public class LogtailResponse {

    private String error;
    private int status;

    public LogtailResponse(String error, int status) {
        this.error = error;
        this.status = status;
    }

    public String getError() { return error; }
    public int getStatus() { return status; }

}
