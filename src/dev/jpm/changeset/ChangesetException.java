package dev.jpm.changeset;

/** A problem jpm changeset reports and exits on, without a stack trace. */
public final class ChangesetException extends RuntimeException {
    public ChangesetException(String message) {
        super(message);
    }
}
