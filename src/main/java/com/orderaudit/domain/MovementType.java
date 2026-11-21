package com.orderaudit.domain;

/** Why stock moved. Each type has a rule about the direction (sign) of the quantity. */
public enum MovementType {
    OPENING,      // opening balance, stock in
    PURCHASE,     // raw material or packaging received, stock in
    PROD_ISSUE,   // raw material or packaging consumed by a production run, stock out
    PROD_OUTPUT,  // good packets produced, stock in
    SALE,         // goods dispatched to a customer, stock out
    RETURN,       // goods returned, stock in
    ADJUST,       // manual correction, either direction
    REVERSAL;     // cancels an earlier movement: exactly the opposite quantity

    /** True if this quantity has the right direction for this type. Zero is never allowed. */
    public boolean allows(long quantitySigned) {
        return switch (this) {
            case OPENING, PURCHASE, PROD_OUTPUT, RETURN -> quantitySigned > 0;
            case PROD_ISSUE, SALE -> quantitySigned < 0;
            case ADJUST, REVERSAL -> quantitySigned != 0;
        };
    }
}
