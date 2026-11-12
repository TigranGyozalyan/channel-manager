package com.tgyozalyan.validation;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class Validation {

    private static final Map<String, Pattern> patterns = new HashMap<>();

    public static void positive(ValidationResult r, Integer val, String name) {
        positive(r, val != null ? Long.valueOf(val) : null, name);
    }

    public static void positive(ValidationResult r, Long val, String name) {
        if (val == null) {
            return;
        }
        if (val <= 0) {
            r.addError(name, "must be greater than 0");
        }
    }

    public static void min(ValidationResult r, Integer val, String name, long min) {
        min(r, val != null ? Long.valueOf(val) : null, name, min);
    }

    public static void min(ValidationResult r, Long val, String name, long min) {
        if (val == null) {
            return;
        }
        if (val < min) {
            r.addError(name, "must be greater than or equal to " + min);
        }
    }

    public static void max(ValidationResult r, Integer val, String name, long max) {
        max(r, val != null ? Long.valueOf(val) : null, name, max);
    }

    public static void max(ValidationResult r, Long val, String name, long max) {
        if (val == null) {
            return;
        }
        if (val > max) {
            r.addError(name, "must be less than or equal to " + max);
        }
    }

    public static <T> void notNull(ValidationResult r, T val, String name) {
        if (val == null) {
            r.addError(name, "must not be null");
        }
    }

    public static void notBlank(ValidationResult r, String val, String name) {
        if (val == null || val.isBlank()) {
            r.addError(name, "must not be blank");
        }
    }

    public static void pattern(ValidationResult r, String val, String name, String regex) {
        if (val == null) {
            return;
        }
        Pattern pattern = patterns.computeIfAbsent(regex, Pattern::compile);;
        if (pattern.matcher(val).matches()) {
            return;
        }
        r.addError(name, "must match pattern " + pattern.pattern());
    }

    public static void email(ValidationResult r, String val, String name) {
        if (val == null) {
            return;
        }
        Pattern pattern = patterns.computeIfAbsent("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", Pattern::compile);;
        if (pattern.matcher(val).matches()) {
            return;
        }
        r.addError(name, "must be a well-formed email address");
    }

    public static void size(ValidationResult r, String val, String name, long min, long max) {
        if (val == null) {
            return;
        }
        if (val.length() > max) {
            r.addError(name, "max allowed size is " + max);
        }
        if (val.length() < min) {
            r.addError(name, "min required size is " + min);
        }
    }
}
