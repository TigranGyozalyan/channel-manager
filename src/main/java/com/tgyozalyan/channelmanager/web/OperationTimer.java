package com.tgyozalyan.channelmanager.web;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class OperationTimer {

    private final Map<String, OpDuration> timers = new HashMap<>();

    public <T> T timed(String key, SqlAwareSupplier<T> runnable) throws SQLException {
        long start = System.nanoTime();
        T result = runnable.run();
        timers.put(key, new OpDuration(start, System.nanoTime()));
        return result;
    }

    public void timed(String key, SqlAwareRunnable runnable) throws SQLException {
        long start = System.nanoTime();
        runnable.run();
        timers.put(key, new OpDuration(start, System.nanoTime()));
    }

    public void add(String key, Long start, Long end) {
        timers.put(key, new OpDuration(start, end));
    }

    public String timingHeader() {
        return timers.entrySet().stream()
                .map((k) -> duration(k.getKey(), k.getValue().start(), k.getValue().end()))
                .collect(Collectors.joining(","));
    }

    public boolean isEmpty() {
        return timers.isEmpty();
    }

    private static String duration(String durname, Long start, long now) {
        if (start != null)
            return durname+";dur="+((double)(now - start))/1e6;
        return "";
    }

    @FunctionalInterface
    public interface SqlAwareSupplier<T> {

        T run() throws SQLException;
    }

    @FunctionalInterface
    public interface SqlAwareRunnable {

        void run() throws SQLException;
    }

    public record OpDuration(long start, long end) { }
}
