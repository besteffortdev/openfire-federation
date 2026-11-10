package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.session.DomainPair;
import org.jivesoftware.openfire.session.IncomingServerSession;
import org.jivesoftware.openfire.session.OutgoingServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;

/**
 * Trust on first use for peers' S2S identities: pins the public key of each peer's leaf certificate
 * when first seen, and untrusts the peer when the key changes until the admin accepts the new one.
 */
public final class CertificatePinning {

    private static final Logger Log = LoggerFactory.getLogger(CertificatePinning.class);

    /** Prefix of a pin in the leaf-key format, as opposed to a legacy certificate hash. */
    private static final String SPKI_PREFIX = "spki:";

    private final FederationManager manager;

    CertificatePinning(FederationManager manager) {
        this.manager = manager;
    }

    /**
     * Pins the peer's S2S leaf key on first sight and checks it afterwards. A changed key untrusts the
     * peer until the admin accepts it; a matching legacy pin is upgraded silently.
     */
    public void observe(String domain) {
        PeerRegistry peers = manager.getPeerRegistry();
        Certificate[] chain = peerCertChain(domain);
        if (chain == null || chain.length == 0) {
            if (isDowngrade(peers.getPinnedCertFp(domain), outgoingLinkState(domain))) flagDowngrade(domain);
            return;
        }
        String fp = leafSpkiFp(chain);
        if (fp == null) return;
        peers.setLastSeenCertFp(domain, fp);

        String pinned = peers.getPinnedCertFp(domain);
        if (pinned == null || pinned.isBlank()) {
            peers.setPinnedCertFp(domain, fp);
            peers.setCertMismatch(domain, false);
            Log.info("Pinned S2S leaf key for {} ({}…)", domain, abbrevFp(fp));
            return;
        }
        if (pinned.equals(fp)) {
            peers.setCertMismatch(domain, false);
            return;
        }
        if (!pinned.startsWith(SPKI_PREFIX) && pinned.equals(sha256Hex(chain[chain.length - 1]))) {
            peers.setPinnedCertFp(domain, fp);
            peers.setCertMismatch(domain, false);
            Log.info("Upgraded S2S pin for {} from legacy cert-hash to leaf-SPKI ({}…)", domain, abbrevFp(fp));
            return;
        }
        if (!peers.isCertMismatch(domain)) {
            peers.setCertMismatch(domain, true);
            Log.warn("SECURITY: S2S identity key for {} changed (pinned={}…, seen={}…) — auto-untrusting; "
                   + "admin must accept the new key to restore", domain, abbrevFp(pinned), abbrevFp(fp));
            if (!peers.isUntrusted(domain)) {
                peers.setUntrusted(domain, true);
                manager.applyLocalTrustChange(domain);
            }
        }
    }

    /** What is known of our own connection to a peer; an incoming-only link proves nothing either way. */
    enum OutgoingLink { NONE, ENCRYPTED, PLAINTEXT }

    /**
     * Whether a peer with a pinned key is now reached over an unencrypted link we opened ourselves.
     * Such a link presents no certificate, so without this check an impersonator could avoid the pin
     * simply by not offering TLS.
     */
    static boolean isDowngrade(String pinned, OutgoingLink link) {
        return pinned != null && !pinned.isBlank() && link == OutgoingLink.PLAINTEXT;
    }

    private static OutgoingLink outgoingLinkState(String domain) {
        try {
            OutgoingServerSession out = XMPPServer.getInstance().getSessionManager()
                    .getOutgoingServerSession(new DomainPair(localDomain(), domain));
            if (out == null) return OutgoingLink.NONE;
            return out.isEncrypted() ? OutgoingLink.ENCRYPTED : OutgoingLink.PLAINTEXT;
        } catch (RuntimeException e) {
            Log.debug("Could not read the S2S link state of {}", domain, e);
            return OutgoingLink.NONE;
        }
    }

    /** Untrusts a pinned peer whose link lost its encryption, as a changed key does. */
    private void flagDowngrade(String domain) {
        PeerRegistry peers = manager.getPeerRegistry();
        if (peers.isCertMismatch(domain)) return;
        peers.setCertMismatch(domain, true);
        Log.warn("SECURITY: the S2S link to {} is no longer encrypted although a key is pinned for it — "
               + "auto-untrusting. Restore TLS on that link and make the peer trusted again, or remove and "
               + "re-add the peer to forget the pin", domain);
        if (!peers.isUntrusted(domain)) {
            peers.setUntrusted(domain, true);
            manager.applyLocalTrustChange(domain);
        }
    }

    /** The admin accepted a changed key: pins the last observed fingerprint and clears the alert. */
    public void acceptNewCertificate(String domain) {
        PeerRegistry peers = manager.getPeerRegistry();
        String fp = peers.getPeer(domain).map(PeerServer::getLastSeenCertFp).orElse(null);
        if (fp == null || fp.isBlank()) {
            Log.warn("accept-cert for {} ignored — no observed certificate to pin", domain);
            return;
        }
        peers.setPinnedCertFp(domain, fp);
        peers.setCertMismatch(domain, false);
        Log.info("Admin accepted new S2S identity key for {} ({}…)", domain, abbrevFp(fp));
    }

    /** The certificate chain the peer presents on S2S, preferring the outgoing session, or null. */
    private static Certificate[] peerCertChain(String domain) {
        SessionManager sm = XMPPServer.getInstance().getSessionManager();
        try {
            OutgoingServerSession out = sm.getOutgoingServerSession(new DomainPair(localDomain(), domain));
            if (out != null) {
                Certificate[] c = out.getPeerCertificates();
                if (c != null && c.length > 0) return c;
            }
            for (IncomingServerSession in : sm.getIncomingServerSessions(domain)) {
                Certificate[] c = in.getPeerCertificates();
                if (c != null && c.length > 0) return c;
            }
        } catch (RuntimeException e) {
            Log.debug("Could not read the S2S certificates of {}", domain, e);
        }
        return null;
    }

    /** {@code "spki:<sha256>"} of the leaf certificate's public key, or null. */
    private static String leafSpkiFp(Certificate[] chain) {
        if (chain == null || chain.length == 0 || chain[0].getPublicKey() == null) return null;
        String hex = sha256Hex(chain[0].getPublicKey().getEncoded());
        return hex == null ? null : SPKI_PREFIX + hex;
    }

    /** SHA-256 hex of a certificate's encoding (the legacy pin format), or null. */
    private static String sha256Hex(Certificate cert) {
        try {
            return sha256Hex(cert.getEncoded());
        } catch (CertificateEncodingException e) {
            Log.warn("Peer certificate could not be encoded for pinning", e);
            return null;
        }
    }

    /** SHA-256 of {@code data} as lowercase hex, or null when the platform lacks SHA-256. */
    private static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every JVM must ship SHA-256; without it certificate pinning silently stops working.
            Log.error("SHA-256 unavailable — certificate pinning is disabled", e);
            return null;
        }
    }

    /** The first 12 hex characters of a fingerprint, for logging. */
    private static String abbrevFp(String fp) {
        String hex = fp.startsWith(SPKI_PREFIX) ? fp.substring(SPKI_PREFIX.length()) : fp;
        return hex.substring(0, Math.min(12, hex.length()));
    }
}
