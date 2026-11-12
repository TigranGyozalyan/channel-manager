package com.tgyozalyan.validation;

import io.elsci.boardeaux.err.ValidationError;
import io.elsci.boardeaux.err.ValidationException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class ValidationResult {

    private final Map<String, LinkedHashSet<String>> errors = new HashMap<>();

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public void addError(String field, String message) {
        errors.computeIfAbsent(field, _ -> new LinkedHashSet<>())
                .add(message);
    }

    public ValidationException toException() {
        List<ValidationError> exErrors = new ArrayList<>();

        for (Map.Entry<String, LinkedHashSet<String>> entry : errors.entrySet()) {
            entry.getValue().forEach(val -> exErrors.add(ValidationError.error(entry.getKey(), val)));
        }

        return new ValidationException(exErrors);
    }

    public void throwIfErrors() {
        if (hasErrors()) {
            throw toException();
        }
    }
}
