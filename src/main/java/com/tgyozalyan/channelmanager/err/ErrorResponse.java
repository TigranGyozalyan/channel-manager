package com.tgyozalyan.channelmanager.err;

import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ErrorResponse {
    private List<ValidationError> errors = new ArrayList<>();
    /** If we show an error in a popup - this summary will be the title. */
    public String errorSummary, errorMessage, stackTrace, errorCode;

    public ErrorResponse() {}

    public ErrorResponse(Throwable e){
        errorMessage = e.getMessage();
        stackTrace = ExceptionUtils.getStackTrace(e);
    }

    public ErrorResponse(String errorMessage) {
        this(errorMessage, Collections.emptyList());
    }

    public ErrorResponse(String errorMessage, List<ValidationError> errors) {
        this(null, errorMessage, errors);
    }
    public ErrorResponse(String errorSummary, String errorMessage, List<ValidationError> errors) {
        this(errorSummary, errorMessage, errors, null);
    }
    public ErrorResponse(String errorSummary, String errorMessage, List<ValidationError> errors, String errorCode) {
        this.errorSummary = errorSummary;
        this.errorMessage = errorMessage;
        this.errors.addAll(errors);
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<ValidationError> getErrors() {
        return errors;
    }
}
