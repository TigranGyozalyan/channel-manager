package com.tgyozalyan.channelmanager.db;

import io.elsci.web.ErrorCode;

import java.sql.SQLException;
import java.util.Set;

public class PgErrorParser {
    public static final String DB_ERROR_PREFIX_ELSCI_401 = "ERROR: [ELSCI-401] ";
    public static final String DB_ERROR_PREFIX_ELSCI_402 = "ERROR: [ELSCI-402] ";
    public static final String DB_ERROR_PREFIX_ELSCI_403 = "ERROR: [ELSCI-403] ";
    public static final String DB_ERROR_PREFIX_ELSCI_404 = "ERROR: [ELSCI-404] ";
    public static final String DB_ERROR_PREFIX_ELSCI_400 = "ERROR: [ELSCI-400] ";
    public static final String DB_ERROR_GENERIC_ELSCI_PREFIX = "ERROR: [ELSCI-";
    private static final int ERROR_PREFIX_LENGTH = DB_ERROR_PREFIX_ELSCI_403.length();

    /** Reads the message of the exception and checks if it requires the caller to authenticate first. */
    public static boolean isAuthenticationRequiredException(SQLException e) {
        String message = e.getMessage();
        return message != null && message.contains(DB_ERROR_PREFIX_ELSCI_401);
    }
    /** Reads the message of the exception and checks if it is access denied exception. */
    public static boolean isAccessDeniedException(SQLException e) {
        String message = e.getMessage();
        return message != null && message.contains(DB_ERROR_PREFIX_ELSCI_403);
    }
    /** Reads the message of the exception and checks if it is entity not found exception. */
    public static boolean isEntityNotFoundException(SQLException e) {
        String message = e.getMessage();
        return message != null && message.contains(DB_ERROR_PREFIX_ELSCI_404);
    }
    public static boolean isValidationException(SQLException e) {
        String message = e.getMessage();
        return message != null && message.contains(DB_ERROR_PREFIX_ELSCI_400);
    }
    public static boolean isUsageLimitsException(SQLException e) {
        String message = e.getMessage();
        return message != null && message.contains(DB_ERROR_PREFIX_ELSCI_402);
    }
    /** Reads the message of the exception and returns the part of the message that should be sent to the client. */
    public static String getDbErrorMessageForUi(SQLException e) {
        String message = e.getMessage();
        int messageStart = message.indexOf(DB_ERROR_GENERIC_ELSCI_PREFIX);
        if (messageStart != -1) {
            return message.substring(messageStart + ERROR_PREFIX_LENGTH, message.indexOf("\n", messageStart));
        }
        return message;
    }

    /** True when the exception violates a unique constraint or a unique index (SQLSTATE 23505). */
    public static boolean isUniqueViolation(SQLException e) {
        return "23505".equals(extractSqlState(e));
    }
    /** Name of the violated constraint/index parsed from the PG error message, or null if it can't be determined. */
    public static String extractConstraintName(SQLException e) {
        return doExtractConstraintName(e);
    }

    /** Partially based on Hibernate's {@link org.hibernate.exception.internal.SQLStateConversionDelegate} */
    public static boolean isConstraintViolation(SQLException e) {
        String state = extractSqlState(e);
        if(state == null)
            return false;
        return Set.of("23", "27", "44").contains(determineSqlStateClassCode(state));
    }
    /** Was shamelessly copied from Hibernate's {@link JdbcExceptionHelper}. */
    public static String determineSqlStateClassCode(String sqlState) {
        return sqlState != null && sqlState.length() >= 2 ? sqlState.substring(0, 2) : sqlState;
    }
    /** Was shamelessly copied from Hibernate's {@link JdbcExceptionHelper}. */
    private static String extractSqlState(SQLException sqlException) {
        String sqlState = sqlException.getSQLState();
        for(SQLException nested = sqlException.getNextException(); sqlState == null && nested != null; nested = nested.getNextException()) {
            sqlState = nested.getSQLState();
        }
        return sqlState;
    }
    /** Was shamelessly copied from Hibernate's {@link org.hibernate.dialect.PostgreSQL81Dialect} and modified a little*/
    private static String doExtractConstraintName(SQLException sqle) throws NumberFormatException {
        int sqlState = Integer.parseInt(JdbcExceptionHelper.extractSqlState(sqle));
        return switch (sqlState) {
            case 23001 -> null;
            case 23502 -> extractUsingTemplate("null value in column \"", "\" violates not-null constraint", sqle.getMessage());
            case 23503 -> extractUsingTemplate("violates foreign key constraint \"", "\"", sqle.getMessage());
            case 23505 -> extractUsingTemplate("violates unique constraint \"", "\"", sqle.getMessage());
            case 23514 -> extractUsingTemplate("violates check constraint \"", "\"", sqle.getMessage());
            default -> null;
        };
    }
    /** Was shamelessly copied from Hibernate's {@link org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtracter} */
    private static String extractUsingTemplate(String templateStart, String templateEnd, String message) {
        int templateStartPosition = message.indexOf(templateStart);
        if (templateStartPosition < 0) {
            return null;
        } else {
            int start = templateStartPosition + templateStart.length();
            int end = message.indexOf(templateEnd, start);
            if (end < 0) {
                end = message.length();
            }

            return message.substring(start, end);
        }
    }

    public static ErrorCode getBillingErrorCode(SQLException e) {
        String message = e.getMessage();
        if (message.startsWith("ERROR: [ELSCI-402]") && message.contains("boards, consider upgrading."))
            return ErrorCode.billing_max_boards_exceeded;
        else if (message.startsWith("ERROR: [ELSCI-402]") && message.contains("MB, consider upgrading."))
            return ErrorCode.billing_max_attachment_size_exceeded;
        else if (message.startsWith("ERROR: [ELSCI-402]") && message.contains("cards, consider upgrading."))
            return ErrorCode.billing_max_tasks_per_board_exceeded;
        else if (message.startsWith("ERROR: [ELSCI-402]") && message.contains("MB storage limit per board, consider upgrading."))
            return ErrorCode.billing_max_storage_per_board_exceeded;
        else
            return ErrorCode.billing_generic_error;
    }
}