package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.CertificatePinning.OutgoingLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a link that presents no certificate counts against a pinned peer. */
class CertificatePinningTest {

    @Test
    @DisplayName("a pinned peer reached over a link we opened without encryption is a downgrade")
    void plaintextToPinnedPeer() {
        assertTrue(CertificatePinning.isDowngrade("spki:ab12", OutgoingLink.PLAINTEXT));
    }

    @Test
    @DisplayName("an unpinned peer, or one we have not dialled yet, is not flagged")
    void nothingToCompare() {
        assertFalse(CertificatePinning.isDowngrade(null, OutgoingLink.PLAINTEXT));
        assertFalse(CertificatePinning.isDowngrade("", OutgoingLink.PLAINTEXT));
        assertFalse(CertificatePinning.isDowngrade("spki:ab12", OutgoingLink.NONE), "incoming-only link");
        assertFalse(CertificatePinning.isDowngrade("spki:ab12", OutgoingLink.ENCRYPTED));
    }
}
