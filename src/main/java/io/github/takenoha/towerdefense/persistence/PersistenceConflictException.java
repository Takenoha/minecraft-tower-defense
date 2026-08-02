package io.github.takenoha.towerdefense.persistence;

/** A valid request which conflicts with already-persisted ownership or position data. */
public final class PersistenceConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PersistenceConflictException(String message) {
        super(message);
    }

    public PersistenceConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
