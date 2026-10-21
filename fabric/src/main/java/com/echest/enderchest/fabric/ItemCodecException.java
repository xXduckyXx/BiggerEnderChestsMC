package com.echest.enderchest.fabric;

/**
 * Thrown when an item cannot be serialized or deserialized.
 */
public class ItemCodecException extends Exception {

    /**
     * Creates the exception.
     *
     * @param message the message
     */
    public ItemCodecException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the message
     * @param cause   the cause
     */
    public ItemCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
