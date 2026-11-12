package com.tgyozalyan.channelmanager.util;

public interface Utils {
    static <T> T defaultIfNull(T a, T thedefault) {
        return a != null ? a : thedefault;
    }
}
