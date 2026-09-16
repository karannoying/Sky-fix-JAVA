package com.skyfix.domain.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checked root of every failure SKYFIX reports to a user.
 *
 * <p>Carries an ordered context map so a message can name the offending field, and an optional
 * {@code file:line} origin so a parse failure points at the exact input line (NFR-3). Subclasses
 * fix the process exit code; see {@link #exitCode()}.
 *
 * <p>{@link IllegalArgumentException} is reserved for programmer errors in private methods and is
 * never used for user input (CLAUDE.md coding conventions).
 */
public abstract class SkyfixException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Map<String, String> context = new LinkedHashMap<>();
    private final String sourceFile;
    private final int sourceLine;

    protected SkyfixException(String message) {
        this(message, null, 0, null);
    }

    protected SkyfixException(String message, Throwable cause) {
        this(message, null, 0, cause);
    }

    protected SkyfixException(String message, String sourceFile, int sourceLine, Throwable cause) {
        super(message, cause);
        this.sourceFile = sourceFile;
        this.sourceLine = sourceLine;
    }

    /** Process exit code this failure maps to, per BLUEPRINT §12. */
    public abstract int exitCode();

    /**
     * Adds a context entry and returns {@code this}, so a throw site can read as one expression.
     *
     * @param key   context key, for example {@code "field"}
     * @param value context value, rendered verbatim in {@link #userMessage()}
     * @return this exception
     */
    public SkyfixException with(String key, Object value) {
        context.put(key, String.valueOf(value));
        return this;
    }

    /** Unmodifiable view of the context entries, in insertion order. */
    public Map<String, String> context() {
        return Map.copyOf(context);
    }

    /** Source file this failure was found in, or {@code null} if it did not come from a file. */
    public String sourceFile() {
        return sourceFile;
    }

    /** 1-based line number within {@link #sourceFile()}, or 0 if not applicable. */
    public int sourceLine() {
        return sourceLine;
    }

    /**
     * One line for the console: {@code file:line: message [key=value, ...]}.
     *
     * <p>This is what the CLI prints. A user never sees a stack trace (NFR-3).
     *
     * @return the rendered message, never {@code null}
     */
    public String userMessage() {
        StringBuilder sb = new StringBuilder();
        if (sourceFile != null) {
            sb.append(sourceFile);
            if (sourceLine > 0) {
                sb.append(':').append(sourceLine);
            }
            sb.append(": ");
        }
        sb.append(getMessage());
        if (!context.isEmpty()) {
            sb.append(" [");
            boolean first = true;
            for (Map.Entry<String, String> e : context.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }
}
