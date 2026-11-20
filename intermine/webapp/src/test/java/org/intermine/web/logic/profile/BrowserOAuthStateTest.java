package org.intermine.web.logic.profile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;
import org.intermine.web.logic.oauth2.OAuthExchangeException;

public class BrowserOAuthStateTest extends TestCase {
    private static final URI REDIRECT = URI.create("https://mine.test/oauth2callback.do?provider=TEST");
    private MutableClock clock;
    private BrowserOAuthState states;
    private OAuthHttpFixture.Browser browser;

    @Override public void setUp() throws Exception {
        clock = new MutableClock();
        states = new BrowserOAuthState(clock);
        browser = new OAuthHttpFixture.Browser();
    }

    public void testNewStatesContainIndependentRandomMaterial() {
        Set<String> issued = new HashSet<>();
        for (int i = 0; i < 32; i++) {
            String state = states.begin(browser.session, "TEST", REDIRECT);
            assertEquals(32, Base64.getUrlDecoder().decode(state).length);
            assertTrue(issued.add(state));
        }
    }

    public void testStateHasOneClaimAndBindsProviderRedirectAndNonce() {
        String state = states.begin(browser.session, "TEST", REDIRECT);
        rejected(() -> states.claim(browser.session, "OTHER", REDIRECT, state));
        rejected(() -> states.claim(browser.session, "TEST", URI.create("https://mine.test/other"), state));
        rejected(() -> states.claim(browser.session, "TEST", REDIRECT, "wrong"));
        rejected(() -> states.claim(browser.session, "TEST", REDIRECT, null));
        BrowserOAuthState.Ticket ticket = states.claim(browser.session, "TEST", REDIRECT, state);
        synchronized (browser.session) { states.requireCurrent(browser.session, ticket); }
        rejected(() -> states.claim(browser.session, "TEST", REDIRECT, state));
    }

    public void testPendingStateExpiresAtExactlyTenMinutes() {
        String state = states.begin(browser.session, "TEST", REDIRECT);
        clock.advance(Duration.ofMinutes(10));
        rejected(() -> states.claim(browser.session, "TEST", REDIRECT, state));
        String fresh = states.begin(browser.session, "TEST", REDIRECT);
        assertNotNull(states.claim(browser.session, "TEST", REDIRECT, fresh));
    }

    public void testClaimedTicketAlsoExpiresDuringExchange() {
        String state = states.begin(browser.session, "TEST", REDIRECT);
        clock.advance(Duration.ofSeconds(599));
        BrowserOAuthState.Ticket ticket = states.claim(browser.session, "TEST", REDIRECT, state);
        synchronized (browser.session) { states.requireCurrent(browser.session, ticket); }
        clock.advance(Duration.ofSeconds(1));
        synchronized (browser.session) {
            rejected(() -> states.requireCurrent(browser.session, ticket));
        }
    }

    public void testNewInitiationInvalidatesAlreadyClaimedTicket() {
        String state = states.begin(browser.session, "TEST", REDIRECT);
        BrowserOAuthState.Ticket old = states.claim(browser.session, "TEST", REDIRECT, state);
        String newer = states.begin(browser.session, "OTHER", REDIRECT);
        synchronized (browser.session) {
            rejected(() -> states.requireCurrent(browser.session, old));
        }
        BrowserOAuthState.Ticket current = states.claim(browser.session, "OTHER", REDIRECT, newer);
        synchronized (browser.session) { states.requireCurrent(browser.session, current); }
    }

    public void testIdentityTransitionInvalidatesPendingAndClaimedFlows() {
        String pending = states.begin(browser.session, "TEST", REDIRECT);
        BrowserOAuthState.invalidate(browser.session);
        rejected(() -> states.claim(browser.session, "TEST", REDIRECT, pending));
        String state = states.begin(browser.session, "TEST", REDIRECT);
        BrowserOAuthState.Ticket claimed = states.claim(browser.session, "TEST", REDIRECT, state);
        BrowserOAuthState.invalidate(browser.session);
        synchronized (browser.session) {
            rejected(() -> states.requireCurrent(browser.session, claimed));
        }
    }

    public void testConcurrentCallbacksHaveOneClaimant() throws Exception {
        String state = states.begin(browser.session, "TEST", REDIRECT);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> claim = () -> {
                assertTrue(start.await(2, TimeUnit.SECONDS));
                try { states.claim(browser.session, "TEST", REDIRECT, state); return true; }
                catch (OAuthExchangeException expected) { return false; }
            };
            Future<Boolean> first = workers.submit(claim);
            Future<Boolean> second = workers.submit(claim);
            start.countDown();
            int claims = (first.get(2, TimeUnit.SECONDS) ? 1 : 0) + (second.get(2, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, claims);
        } finally { workers.shutdownNow(); }
    }

    public void testSessionSerializationPreservesBindingExpiryAndConsumption() throws Exception {
        String state = states.begin(browser.session, "TEST", REDIRECT);
        roundTripSession();
        rejected(() -> states.claim(browser.session, "OTHER", REDIRECT, state));
        assertNotNull(states.claim(browser.session, "TEST", REDIRECT, state));
        roundTripSession();
        rejected(() -> states.claim(browser.session, "TEST", REDIRECT, state));
        String pending = states.begin(browser.session, "TEST", REDIRECT);
        roundTripSession();
        clock.advance(Duration.ofMinutes(10));
        rejected(() -> states.claim(browser.session, "TEST", REDIRECT, pending));
    }

    private void roundTripSession() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new HashMap<>(browser.sessionValues));
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            Map<?, ?> restored = (Map<?, ?>) in.readObject();
            browser.sessionValues.clear();
            restored.forEach((key, value) -> browser.sessionValues.put((String) key, value));
        }
    }

    private static void rejected(Runnable action) {
        try { action.run(); fail("Invalid or consumed flow must be rejected"); }
        catch (OAuthExchangeException expected) { assertEquals(400, expected.getHttpStatus()); }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
