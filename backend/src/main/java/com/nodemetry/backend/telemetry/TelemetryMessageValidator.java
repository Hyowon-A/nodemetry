package com.nodemetry.backend.telemetry;

import java.util.regex.Pattern;

final class TelemetryMessageValidator {

    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_FIRMWARE_VERSION_LENGTH = 64;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    private static final Pattern SAFE_FIRMWARE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");

    private TelemetryMessageValidator() {}

    static boolean isValid(TelemetryMessage message) {
        return message != null
                && isValidIdentifier(message.messageId())
                && isValidIdentifier(message.nodeId())
                && isValidIdentifier(message.runId())
                && isValidFirmwareVersion(message.firmwareVersion());
    }

    static void requireValid(TelemetryMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }

        requireIdentifier("messageId", message.messageId());
        requireIdentifier("nodeId", message.nodeId());
        requireIdentifier("runId", message.runId());
        requireFirmwareVersion(message.firmwareVersion());
    }

    private static void requireIdentifier(String fieldName, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (!isValidIdentifier(value)) {
            throw new IllegalArgumentException(fieldName + " contains unsupported characters or is too long");
        }
    }

    private static void requireFirmwareVersion(String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("firmwareVersion is required");
        }
        if (!isValidFirmwareVersion(value)) {
            throw new IllegalArgumentException("firmwareVersion contains unsupported characters or is too long");
        }
    }

    private static boolean isValidIdentifier(String value) {
        return !isBlank(value)
                && value.length() <= MAX_IDENTIFIER_LENGTH
                && SAFE_IDENTIFIER.matcher(value).matches();
    }

    private static boolean isValidFirmwareVersion(String value) {
        return !isBlank(value)
                && value.length() <= MAX_FIRMWARE_VERSION_LENGTH
                && SAFE_FIRMWARE_VERSION.matcher(value).matches();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
