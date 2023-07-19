package com.logtail.logback;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

public class LogtailAppenderDecorator extends LogtailAppender {
    private Exception exception;
    private LogtailResponse response;
    protected int apiCalls = 0;

    private ReentrantLock flushLock = new ReentrantLock();

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

    @Override
    public void flush() {
        flushLock.lock();
        super.flush();
        flushLock.unlock();
    }

    public void awaitFlushCompletion(){
        try {
            // Wait a bit for possible asyncFlush to be initialized
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // Ignore interruption
        }
        flushLock.lock();
        flushLock.unlock();
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
