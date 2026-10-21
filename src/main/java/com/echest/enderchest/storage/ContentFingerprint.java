package com.echest.enderchest.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A fingerprint of the content of a vanilla ender chest.
 *
 * <p>The fingerprint is built from the serialized payloads of the items, so it only depends on
 * the items themselves and not on the slot they were in. Two chests with the same items have the
 * same fingerprint, a chest with an extra item has a different one.
 *
 * <p>It is used to recognise content that was already imported. Without it the same items could
 * be imported a second time, for example when the server was killed before the player file with
 * the cleared vanilla ender chest was written. Every import would add another copy of every
 * item.
 *
 * <p>This class is free of any Minecraft dependency and can be unit tested without a server.
 */
public final class ContentFingerprint {

    private ContentFingerprint() {
    }

    /**
     * Builds the fingerprint of a chest.
     *
     * @param payloads the payloads of the slots, {@code null} for an empty slot
     * @return the fingerprint, an empty string for an empty chest
     */
    public static String of(byte[][] payloads) {
        List<String> hashes = new ArrayList<>();
        if (payloads != null) {
            for (byte[] payload : payloads) {
                if (payload == null || payload.length == 0) {
                    continue;
                }
                hashes.add(sha256(payload));
            }
        }
        if (hashes.isEmpty()) {
            return "";
        }
        // The order of the slots must not matter, the same items are the same content.
        Collections.sort(hashes);
        StringBuilder builder = new StringBuilder();
        for (String hash : hashes) {
            builder.append(hash).append(':');
        }
        return builder.toString();
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte part : hash) {
                builder.append(Character.forDigit((part >> 4) & 0xF, 16));
                builder.append(Character.forDigit(part & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
