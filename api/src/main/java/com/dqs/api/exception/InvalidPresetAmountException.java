package com.dqs.api.exception;

/**
 * A price was sent for a line that may not be priced that way, or a figure that
 * is not one of the amounts the product is actually sold at.
 *
 * Its own type rather than IllegalArgumentException because it has to answer
 * 400: it is the caller's figure that is wrong, not our state. A blanket
 * IllegalArgumentException handler would turn every genuine bug of ours into a
 * 400 as well.
 */
public class InvalidPresetAmountException extends RuntimeException {

    public InvalidPresetAmountException(String message) {
        super(message);
    }
}
