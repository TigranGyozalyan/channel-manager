package com.tgyozalyan.channelmanager.db;

public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String entityType, String id) {
        this("Couldn't find " + entityType + "#" + id);
    }
    public EntityNotFoundException(Class<?> entity, String id) {
        this(entity.getSimpleName(), id);
    }
    public EntityNotFoundException(String format, Object... args) {
        this(String.format(format, args));
    }
    public EntityNotFoundException(String message) {
        super(message);
    }
}
