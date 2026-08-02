package io.github.takenoha.towerdefense.persistence;

/** Raised when a persistence operation cannot be completed. */
public final class PersistenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
