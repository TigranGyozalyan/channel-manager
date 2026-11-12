package com.tgyozalyan.channelmanager.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.spi.LifeCycle;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class Slf4jOverJulPropagator implements LoggerContextListener, LifeCycle {
    private boolean isStarted = false;

    @Override public void start() {
        // Remove existing handlers attached to j.u.l root logger
        // by default there is only one ConsoleHandler with output to System.err
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        // add SLF4JBridgeHandler to j.u.l's root logger, should be done once during the initialization phase
        SLF4JBridgeHandler.install();
        isStarted = true;
    }
    @Override public void stop() { isStarted = false; }
    @Override public void onStop(LoggerContext context) { }
    @Override public void onStart(LoggerContext context) { }
    @Override public void onReset(LoggerContext context) { }
    @Override public boolean isStarted() { return isStarted; }
    @Override public boolean isResetResistant() { return true; }
    @Override public void onLevelChange(Logger logger, Level level) { }
}
