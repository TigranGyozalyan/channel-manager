package com.tgyozalyan.channelmanager.err;


import org.springframework.validation.FieldError;

import java.beans.ConstructorProperties;
import java.util.Collections;
import java.util.List;

public class ValidationException extends RuntimeException {
    /**
     * Fill it only when there's a particular field you'd like to complain about. Basically for
     * JSR 303 (Bean Validation) type of errors.
     */
    private final List<ValidationError> errors;

    public ValidationException(String message) {
        this(message, Collections.emptyList());
    }
    @ConstructorProperties({"message", "errors"})
    public ValidationException(String message, List<ValidationError> errors) {
        super(message);
        this.errors = errors;
    }

    public ValidationException(List<ValidationError> errors) {
        this.errors = errors;
    }

    public ValidationException(FieldError error) {
        this((List.of(new ValidationError(error))));
    }

    public ValidationException(ValidationError error) {
        this(List.of(error));
    }

    public List<ValidationError> getErrors() {
        return errors;
    }
}
