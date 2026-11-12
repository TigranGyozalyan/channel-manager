package com.tgyozalyan.channelmanager.web.filter;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;

/** @see TimingResponseHeaderAddingAdvice */
public class RequestTimingListener implements ServletRequestListener {
    public static final ThreadLocal<Long> START_TIME = new ThreadLocal<>();

    public void requestInitialized(ServletRequestEvent sre) {
        START_TIME.set(System.nanoTime());
    }
}
