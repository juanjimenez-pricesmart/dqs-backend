package com.dqs.api.exception;

/**
 * A season that this club may not tag a quotation with — inactive, or not
 * assigned to it.
 *
 * Its own type, answering 400, for the same reason as
 * {@link InvalidPresetAmountException}: the caller's value is wrong, not our
 * state, and a blanket IllegalArgumentException handler would turn our own bugs
 * into 400s too.
 */
public class InvalidSeasonException extends RuntimeException {

    public InvalidSeasonException(String message) {
        super(message);
    }
}
