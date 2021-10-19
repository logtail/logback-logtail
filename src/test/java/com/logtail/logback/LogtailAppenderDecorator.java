
package com.logtail.logback;

import javax.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

public class LogtailAppenderDecorator extends LogtailAppender {

    private Throwable exception;

    private Response response;

    private LogtailResponse logtailResponse;

    @Override
    protected Response callIngestApi(String jsonData) {

        try {

            this.response = super.callIngestApi(jsonData);
            return response;

        } catch (Throwable t) {

            this.exception = t;
            throw t;

        }

    }

    @Override
    protected LogtailResponse convertResponseToObject(Response response) throws JsonProcessingException, JsonMappingException {
        this.logtailResponse = super.convertResponseToObject(response);
        return this.logtailResponse;
    }

    public boolean hasException() {
        return this.exception != null;
    }

    public boolean hasError() {
        return this.logtailResponse != null && this.logtailResponse.getError() != null;
    }

    public boolean isOK() {
        return this.response != null && this.response.getStatus() == 202;
    }

    public Throwable getException() {
        return exception;
    }

    public Response getResponse() {
        return response;
    }

    public LogtailResponse getLogtailResponse() {
        return logtailResponse;
    }

}
