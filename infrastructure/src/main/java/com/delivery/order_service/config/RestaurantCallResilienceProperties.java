package com.delivery.order_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.resilience.restaurant")
public class RestaurantCallResilienceProperties {
    private long timeoutMs = 2000;
    private float failureRateThreshold = 50;
    private int slidingWindowSize = 20;
    private long waitOpenSeconds = 30;
    private int permittedHalfOpenCalls = 5;
    private int maxConcurrentCalls = 8;

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = Math.max(100, Math.min(timeoutMs, 30_000)); }
    public float getFailureRateThreshold() { return failureRateThreshold; }
    public void setFailureRateThreshold(float failureRateThreshold) {
        this.failureRateThreshold = Float.isFinite(failureRateThreshold)
                ? Math.max(1, Math.min(failureRateThreshold, 100)) : 50;
    }
    public int getSlidingWindowSize() { return slidingWindowSize; }
    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = Math.max(2, Math.min(slidingWindowSize, 1000));
    }
    public long getWaitOpenSeconds() { return waitOpenSeconds; }
    public void setWaitOpenSeconds(long waitOpenSeconds) {
        this.waitOpenSeconds = Math.max(1, Math.min(waitOpenSeconds, 3600));
    }
    public int getPermittedHalfOpenCalls() { return permittedHalfOpenCalls; }
    public void setPermittedHalfOpenCalls(int permittedHalfOpenCalls) {
        this.permittedHalfOpenCalls = Math.max(1, Math.min(permittedHalfOpenCalls, 100));
    }
    public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
    public void setMaxConcurrentCalls(int maxConcurrentCalls) {
        this.maxConcurrentCalls = Math.max(1, Math.min(maxConcurrentCalls, 200));
    }
}
