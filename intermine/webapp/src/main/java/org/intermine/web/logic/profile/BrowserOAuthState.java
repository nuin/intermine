package org.intermine.web.logic.profile;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.servlet.http.HttpSession;

import org.intermine.web.logic.oauth2.OAuthExchangeException;

/**
 * Single-use browser authorization bound to one session identity transition.
 * @author InterMine Team
 */
public final class BrowserOAuthState
{
    private static final String FLOW = BrowserOAuthState.class.getName() + ".flow";
    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Clock clock;

    /** Create state using the system UTC clock. */
    public BrowserOAuthState() {
        this(Clock.systemUTC());
    }

    /** @param clock the clock used to enforce authorization expiry */
    public BrowserOAuthState(Clock clock) {
        this.clock = clock;
    }

    /**
     * Replace any pending or claimed authorization in this session.
     * @param session browser session
     * @param provider configured provider name
     * @param redirect exact callback URI
     * @return random authorization state
     */
    public String begin(HttpSession session, String provider, URI redirect) {
        synchronized (session) {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(FLOW,
                    new Ticket(provider, redirect, clock.instant().plus(LIFETIME), nonce));
            return nonce;
        }
    }

    /**
     * Claim a matching, unexpired authorization before contacting the provider.
     * @param session browser session
     * @param provider callback provider
     * @param redirect exact callback URI
     * @param state returned authorization state
     * @return the one-use claim
     */
    public Ticket claim(HttpSession session, String provider, URI redirect, String state) {
        synchronized (session) {
            Ticket ticket = current(session);
            if (ticket.claimed || !ticket.provider.equals(provider)
                    || !ticket.redirect.equals(redirect) || state == null
                    || !MessageDigest.isEqual(ticket.nonce.getBytes(StandardCharsets.UTF_8),
                            state.getBytes(StandardCharsets.UTF_8))) {
                throw invalid();
            }
            ticket.claimed = true;
            session.setAttribute(FLOW, ticket);
            return ticket;
        }
    }

    /**
     * Check immediately before profile lookup and installation, under the session lock.
     * The caller must retain that lock throughout the identity transition.
     * @param session browser session
     * @param ticket previously claimed authorization
     */
    public void requireCurrent(HttpSession session, Ticket ticket) {
        if (!Thread.holdsLock(session)) {
            throw new IllegalStateException("OAuth completion requires the session lock");
        }
        if (current(session) != ticket || !ticket.claimed) {
            throw invalid();
        }
    }

    /**
     * Cancel authorization when the browser identity changes.
     * @param session browser session
     */
    public static void invalidate(HttpSession session) {
        synchronized (session) {
            session.removeAttribute(FLOW);
        }
    }

    private Ticket current(HttpSession session) {
        Object value = session.getAttribute(FLOW);
        if (!(value instanceof Ticket)) {
            throw invalid();
        }
        Ticket ticket = (Ticket) value;
        if (!clock.instant().isBefore(ticket.expiresAt)) {
            session.removeAttribute(FLOW);
            throw invalid();
        }
        return ticket;
    }

    private static OAuthExchangeException invalid() {
        return new OAuthExchangeException(
                "Invalid or expired OAuth authorization state", 400, null);
    }

    /** A session-persistable authorization claim. */
    public static final class Ticket implements Serializable
    {
        private static final long serialVersionUID = 1L;
        private final String provider;
        private final URI redirect;
        private final Instant expiresAt;
        private final String nonce;
        private boolean claimed;

        private Ticket(String provider, URI redirect, Instant expiresAt, String nonce) {
            this.provider = provider;
            this.redirect = redirect;
            this.expiresAt = expiresAt;
            this.nonce = nonce;
        }
    }
}
