package org.intermine.web.logic.oauth2;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.intermine.api.InterMineAPI;
import org.intermine.api.profile.Profile;
import org.intermine.web.logic.profile.LoginHandler;
import org.intermine.web.struts.oauth2.DelegatedIdentity;
import org.intermine.webservice.server.user.JSONUserFormatter;
import org.json.JSONObject;

/**
 * Separates provider verification from the serialized profile-transfer boundary.
 * @author InterMine Team
 */
public final class OAuthTransactions implements AutoCloseable
{
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private final InterMineAPI api;
    private final OAuthClientSupport client;
    private final Clock clock;
    private final int capacity;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Transaction> entries = new HashMap<>();
    private final ScheduledExecutorService cleanup;
    private boolean closed;

    /** @param api the mine
     * @param properties provider configuration */
    public OAuthTransactions(InterMineAPI api, Properties properties) {
        this(api, properties, Clock.systemUTC(), 10000);
    }

    /** @param api the mine
     * @param properties provider configuration
     * @param clock transaction expiry clock
     * @param capacity maximum live transactions */
    public OAuthTransactions(InterMineAPI api, Properties properties, Clock clock, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("OAuth transaction capacity must be positive");
        }
        this.api = api;
        this.client = new OAuthClientSupport(properties);
        this.clock = clock;
        this.capacity = capacity;
        cleanup = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "oauth-transaction-expiry");
            thread.setDaemon(true);
            return thread;
        });
        cleanup.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }

    /** Bind the source before sending the browser to its provider.
     * @param anonymous source profile, or null when there is no anonymous work
     * @param provider configured provider
     * @param redirect exact callback URI
     * @param state browser-owned correlation nonce
     * @return the private handle and public authorization URL */
    public Map<String, Object> prepare(Profile anonymous, String provider, URI redirect,
            String state) {
        URI authorization = client.authorizationUri(provider, redirect, state);
        expire();
        byte[] bytes = new byte[32];
        synchronized (entries) {
            if (closed || entries.size() >= capacity) {
                throw new OAuthExchangeException("OAuth transactions are unavailable", 503, null);
            }
            String handle;
            do {
                random.nextBytes(bytes);
                handle = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            } while (entries.containsKey(handle));
            entries.put(handle, new Transaction(anonymous, provider, redirect,
                    clock.instant().plus(LIFETIME)));
            return Map.of("handle", handle, "authorizationUrl", authorization.toString());
        }
    }

    /** Verify a code once, without creating an account or transferring work.
     * @param handle private transaction handle
     * @param code provider authorization code */
    public void exchange(String handle, String code) {
        Transaction transaction = find(handle);
        synchronized (transaction) {
            require(transaction, Phase.PREPARED);
            transaction.phase = Phase.EXCHANGING;
        }
        try {
            DelegatedIdentity identity = client.exchange(transaction.provider, code,
                    transaction.redirect);
            synchronized (transaction) {
                require(transaction, Phase.EXCHANGING);
                transaction.identity = identity;
                transaction.phase = Phase.VERIFIED;
            }
        } catch (RuntimeException error) {
            cancel(handle);
            throw error;
        }
    }

    /** Commit a verified transfer once; retries return the same credentials.
     * @param handle private transaction handle
     * @return user, token and renamedLists */
    public Map<String, Object> complete(String handle) {
        Transaction transaction = find(handle);
        synchronized (transaction) {
            require(transaction, Phase.VERIFIED, Phase.COMPLETED);
            if (transaction.phase == Phase.COMPLETED) {
                return new JSONObject(transaction.output).toMap();
            }
            transaction.phase = Phase.COMMITTING;
            try {
                transaction.profile = OAuthIdentityProfiles.getProfile(api, transaction.identity);
                Map<String, String> renamed = LoginHandler.mergeProfiles(transaction.anonymous,
                        transaction.profile).getRenamedBags();
                JSONObject user = new JSONObject(
                        new JSONUserFormatter(transaction.profile).format());
                transaction.token = api.getProfileManager().generate24hrKey(transaction.profile);
                JSONObject result = new JSONObject(Map.of("user", user, "token", transaction.token,
                        "renamedLists", renamed));
                transaction.output = result.toString();
                transaction.identity = null;
                transaction.phase = Phase.COMPLETED;
                return result.toMap();
            } catch (RuntimeException error) {
                cancel(handle);
                throw error;
            }
        }
    }

    /** Mark delivery so expiry will preserve the issued token.
     * @param handle private transaction handle */
    public void acknowledge(String handle) {
        Transaction transaction = find(handle);
        synchronized (transaction) {
            require(transaction, Phase.COMPLETED, Phase.DELIVERED);
            transaction.phase = Phase.DELIVERED;
            transaction.output = null;
        }
    }

    /** Wait for an already-committing transfer and revoke only its token.
     * @param handle private transaction handle; cancellation is idempotent */
    public void cancel(String handle) {
        Transaction transaction;
        synchronized (entries) {
            transaction = entries.get(handle);
        }
        if (transaction != null) {
            synchronized (transaction) {
                discard(handle, transaction, true);
            }
        }
    }

    /** Expire pending transactions and revoke undelivered tokens. */
    public void expire() {
        for (Map.Entry<String, Transaction> entry : snapshot().entrySet()) {
            Transaction transaction = entry.getValue();
            synchronized (transaction) {
                if (!clock.instant().isBefore(transaction.expires)) {
                    discard(entry.getKey(), transaction, transaction.phase != Phase.DELIVERED);
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (entries) {
            closed = true;
        }
        cleanup.shutdownNow();
        for (Map.Entry<String, Transaction> entry : snapshot().entrySet()) {
            Transaction transaction = entry.getValue();
            synchronized (transaction) {
                discard(entry.getKey(), transaction, transaction.phase != Phase.DELIVERED);
            }
        }
    }

    private Map<String, Transaction> snapshot() {
        synchronized (entries) {
            return new HashMap<>(entries);
        }
    }

    private Transaction find(String handle) {
        synchronized (entries) {
            Transaction transaction = entries.get(handle);
            if (transaction == null || closed) {
                throw inactive();
            }
            return transaction;
        }
    }

    private void require(Transaction transaction, Phase... allowed) {
        if (clock.instant().isBefore(transaction.expires)) {
            for (Phase phase : allowed) {
                if (transaction.phase == phase) {
                    return;
                }
            }
        }
        throw inactive();
    }

    private void discard(String handle, Transaction transaction, boolean revoke) {
        if (revoke && transaction.token != null) {
            api.getProfileManager().revokeTemporaryToken(transaction.token, transaction.profile);
        }
        transaction.phase = Phase.CANCELED;
        transaction.identity = null;
        transaction.output = null;
        synchronized (entries) {
            entries.remove(handle, transaction);
        }
    }

    private static OAuthExchangeException inactive() {
        return new OAuthExchangeException("OAuth transaction is no longer available", 409, null);
    }

    private enum Phase
    {
        PREPARED, EXCHANGING, VERIFIED, COMMITTING, COMPLETED, DELIVERED, CANCELED
    }

    private static final class Transaction
    {
        private final Profile anonymous;
        private final String provider;
        private final URI redirect;
        private final Instant expires;
        private Phase phase = Phase.PREPARED;
        private DelegatedIdentity identity;
        private Profile profile;
        private String token;
        private String output;

        private Transaction(Profile anonymous, String provider, URI redirect, Instant expires) {
            this.anonymous = anonymous;
            this.provider = provider;
            this.redirect = redirect;
            this.expires = expires;
        }
    }
}
