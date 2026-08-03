package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** Result of the one-time technical refund of a consumed raid seal. */
public record RaidSealRefundResult(OperationOutcome outcome, RaidSeal returnedSeal) {
    public RaidSealRefundResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(returnedSeal, "returnedSeal");
    }
}
