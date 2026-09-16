package com.skyfix.io;

import com.skyfix.domain.error.DataFormatException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 of an input file, as the hex string the schema stores.
 *
 * <p>Every ingested artefact carries the digest of the bytes it came from, which is what makes a
 * stored run reproducible in the strong sense: the sounding or telemetry a result was computed from
 * can be shown to be the same file, not merely a file with the same name.
 *
 * <p>One definition, shared by every reader — two implementations of the same hash that disagreed
 * about, say, trailing newlines would silently split one dataset into two rows.
 */
public final class FileDigest {

    private FileDigest() {
    }

    /**
     * Hashes a file's bytes.
     *
     * @param path the file
     * @return the lowercase hex SHA-256
     * @throws DataFormatException if the file cannot be read, naming it
     */
    public static String sha256(Path path) throws DataFormatException {
        try {
            return sha256(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new DataFormatException(path.toString(), 0,
                    "cannot be read to hash: " + e.getMessage());
        }
    }

    /**
     * Hashes bytes already in hand.
     *
     * @param bytes the content
     * @return the lowercase hex SHA-256
     */
    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256; its absence is a broken platform, not a user error.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
