package com.echest.enderchest.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ContentFingerprint}.
 *
 * <p>The fingerprint recognises content that was already imported. Importing the same content a
 * second time would duplicate every item.
 */
class ContentFingerprintTest {

    private static byte[][] payloads(byte[]... values) {
        return values;
    }

    @Test
    void theSameContentHasTheSameFingerprint() {
        String first = ContentFingerprint.of(payloads("diamond".getBytes(), "gold".getBytes()));
        String second = ContentFingerprint.of(payloads("diamond".getBytes(), "gold".getBytes()));

        assertEquals(first, second);
    }

    @Test
    void theOrderOfTheSlotsDoesNotMatter() {
        String first = ContentFingerprint.of(payloads("diamond".getBytes(), "gold".getBytes()));
        String second = ContentFingerprint.of(payloads("gold".getBytes(), "diamond".getBytes()));

        assertEquals(first, second, "the same items are the same content, whatever slot they are in");
    }

    @Test
    void otherContentHasAnotherFingerprint() {
        String first = ContentFingerprint.of(payloads("diamond".getBytes()));
        String second = ContentFingerprint.of(payloads("emerald".getBytes()));

        assertNotEquals(first, second);
    }

    @Test
    void anExtraItemChangesTheFingerprint() {
        String first = ContentFingerprint.of(payloads("diamond".getBytes()));
        String second = ContentFingerprint.of(payloads("diamond".getBytes(), "diamond".getBytes()));

        assertNotEquals(first, second, "a second copy of the same item is more content");
    }

    @Test
    void anEmptyChestHasNoFingerprint() {
        assertEquals("", ContentFingerprint.of(new byte[27][]));
        assertEquals("", ContentFingerprint.of(null));
    }

    @Test
    void theFingerprintIsNotEmpty() {
        assertTrue(!ContentFingerprint.of(payloads("diamond".getBytes())).isEmpty());
    }
}
