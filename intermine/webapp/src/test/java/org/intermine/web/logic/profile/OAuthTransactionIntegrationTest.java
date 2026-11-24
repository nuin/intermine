package org.intermine.web.logic.profile;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.intermine.api.InterMineAPITestCase;
import org.intermine.api.profile.BagState;
import org.intermine.api.profile.InterMineBag;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.SavedQuery;
import org.intermine.model.testmodel.Employee;
import org.intermine.objectstore.ObjectStoreWriter;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.pathquery.PathQuery;
import org.intermine.web.context.InterMineContext;
import org.intermine.web.logic.ClassResourceOpener;
import org.intermine.web.logic.config.WebConfig;
import org.intermine.web.logic.oauth2.OAuthTransactions;
import org.intermine.webservice.server.core.JSONService;
import org.intermine.webservice.server.user.LogoutService;
import org.intermine.webservice.server.oauth2.TransactionService;
import org.intermine.webservice.server.oauth2.TransactionServlet;
import org.json.JSONObject;

public class OAuthTransactionIntegrationTest extends InterMineAPITestCase {
    private OAuthHttpFixture provider;
    private OAuthTransactions transactions;
    private Profile anonymous;
    private String anonymousToken;
    private Integer employeeId;
    private final MutableClock clock = new MutableClock();
    private final List<String> logs = new ArrayList<>();
    private AppenderSkeleton appender;
    private Level oldLevel;

    public OAuthTransactionIntegrationTest(String name) { super(name); }

    @Override public void setUp() throws Exception {
        super.setUp();
        provider = new OAuthHttpFixture();
        InterMineContext.initialise(im, provider.properties("TEST"), new WebConfig(),
                new ClassResourceOpener(getClass()));
        transactions = new OAuthTransactions(im, provider.properties("TEST"), clock, 2);
        anonymous = im.getProfileManager().createAnonymousProfile();
        anonymousToken = im.getProfileManager().generate24hrKey(anonymous);
        ObjectStoreWriter writer = os.getNewWriter();
        try {
            Employee employee = new Employee();
            employee.setName("Deferred transfer employee");
            writer.store(employee);
            employeeId = employee.getId();
        } finally {
            writer.close();
        }
        InterMineBag bag = new InterMineBag("guest", "Employee", "Anonymous work",
                new Date(1700000000000L), BagState.CURRENT, os, null, uosw, List.of("name"));
        bag.addIdToBag(employeeId, "Employee");
        anonymous.saveBag(bag);
        anonymous.getPreferences().put("theme", "dark");
        PathQuery query = new PathQuery(im.getModel());
        query.addView("Employee.name");
        anonymous.saveHistory(new SavedQuery("guest-history", new Date(), query));
        Logger root = Logger.getRootLogger();
        oldLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender = new AppenderSkeleton() {
            @Override protected void append(LoggingEvent event) {
                synchronized (logs) {
                    logs.add(event.getRenderedMessage());
                    if (event.getThrowableStrRep() != null) {
                        logs.add(String.join("\n", event.getThrowableStrRep()));
                    }
                }
            }
            @Override public void close() { }
            @Override public boolean requiresLayout() { return false; }
        };
        root.addAppender(appender);
    }

    @Override public void tearDown() throws Exception {
        Logger.getRootLogger().removeAppender(appender);
        Logger.getRootLogger().setLevel(oldLevel);
        transactions.close();
        InterMineContext.doShutdown();
        provider.close();
        super.tearDown();
    }

    private OAuthHttpFixture.Browser call(String operation, Map<String, String> parameters)
        throws Exception {
        OAuthHttpFixture.Browser http = request(operation, parameters);
        new TransactionService(im, transactions).service(http.request, http.response);
        return http;
    }

    private OAuthHttpFixture.Browser request(String operation, Map<String, String> parameters)
        throws Exception {
        OAuthHttpFixture.Browser http = new OAuthHttpFixture.Browser();
        http.method = "POST";
        http.pathInfo = "/" + operation;
        http.parameters.clear();
        http.parameters.putAll(parameters);
        return http;
    }

    private JSONObject success(String operation, Map<String, String> parameters) throws Exception {
        OAuthHttpFixture.Browser http = call(operation, parameters);
        assertEquals(http.text(), 200, http.status);
        assertEquals("no-store", http.responseHeaders.get("Cache-Control"));
        return new JSONObject(http.text()).getJSONObject("output");
    }

    private String prepare() throws Exception {
        JSONObject result = success("prepare", Map.of("provider", "TEST", "token", anonymousToken,
                "redirect_uri", "https://client.example.test/bluegenes/callback?provider=TEST",
                "state", "state-sentinel-abcdefghijklmnopqrstuvwxyz0123456789"));
        assertTrue(result.getString("handle").matches("[A-Za-z0-9_-]{43}"));
        URI authorization = URI.create(result.getString("authorizationUrl"));
        assertEquals(provider.uri("/authorize").getAuthority(), authorization.getAuthority());
        assertTrue(authorization.getRawQuery().contains("state=state-sentinel-"));
        assertFalse(result.getString("authorizationUrl").contains(result.getString("handle")));
        return result.getString("handle");
    }

    private void exchange(String handle) throws Exception {
        success("exchange", Map.of("handle", handle, "code", OAuthHttpFixture.CODE));
    }

    private Profile owner(JSONObject result) {
        return im.getProfileManager().getPermission(result.getString("token"), im.getClassKeys())
                .getProfile();
    }

    public void testExchangeDefersOwnershipAndCompletionTransfersExactlyOnce() throws Exception {
        int profiles = im.getProfileManager().getProfileUserNames().size();
        String handle = prepare();
        exchange(handle);
        assertEquals(profiles, im.getProfileManager().getProfileUserNames().size());
        assertTrue(anonymous.getSavedBags().containsKey("guest"));
        JSONObject result = success("complete", Map.of("handle", handle));
        Profile account = owner(result);
        assertEquals(List.of(employeeId), account.getSavedBags().get("guest").getContentsAsIds());
        assertEquals("dark", account.getPreferences().get("theme"));
        assertEquals(1, account.getHistory().size());
        assertTrue(anonymous.getSavedBags().isEmpty());
        assertTrue(result.similar(success("complete", Map.of("handle", handle))));
        assertEquals(1, account.getSavedBags().size());
        assertEquals(1, account.getHistory().size());
    }

    public void testCancellationBeforeOrAfterExchangePreservesAnonymousWork() throws Exception {
        for (boolean verify : new boolean[] {false, true}) {
            String handle = prepare();
            if (verify) { exchange(handle); }
            success("cancel", Map.of("handle", handle));
            success("cancel", Map.of("handle", handle));
            assertEquals(409, call("complete", Map.of("handle", handle)).status);
            assertTrue(anonymous.getSavedBags().containsKey("guest"));
        }
    }

    public void testCancellationDuringProviderExchangePreventsLaterCompletion() throws Exception {
        String handle = prepare();
        provider.blockToken = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> call("exchange",
                    Map.of("handle", handle, "code", OAuthHttpFixture.CODE)));
            assertTrue(provider.tokenArrived.await(3, TimeUnit.SECONDS));
            success("cancel", Map.of("handle", handle));
            provider.blockToken.countDown();
            assertEquals(409, pending.get(5, TimeUnit.SECONDS).status);
            assertEquals(409, call("complete", Map.of("handle", handle)).status);
            assertTrue(anonymous.getSavedBags().containsKey("guest"));
        } finally {
            provider.blockToken.countDown();
        }
    }

    public void testReplayCannotExchangeTwiceAndCancellationPreservesNewerToken() throws Exception {
        String handle = prepare();
        exchange(handle);
        assertEquals(409, call("exchange", Map.of("handle", handle,
                "code", OAuthHttpFixture.CODE)).status);
        assertEquals(2, provider.requests.size());
        JSONObject result = success("complete", Map.of("handle", handle));
        Profile account = owner(result);
        String newer = im.getProfileManager().generate24hrKey(account);
        success("cancel", Map.of("handle", handle));
        assertFalse(im.getProfileManager().tokenHasMoreUses(result.getString("token")));
        assertEquals(account, im.getProfileManager().getPermission(newer, im.getClassKeys()).getProfile());
    }

    public void testCancellationWaitsForCommittingTransferAndRevokesItsToken() throws Exception {
        CountDownLatch transferring = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Profile blocking = new Profile(im.getProfileManager(), null, null, null, Profile.NO_QUERIES,
                new HashMap<>(anonymous.getSavedBags()), Profile.NO_TEMPLATES, null, true, false) {
            @Override public void deleteBag(String name) throws ObjectStoreException {
                transferring.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Transfer barrier not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                super.deleteBag(name);
            }
        };
        anonymousToken = im.getProfileManager().generate24hrKey(blocking);
        String handle = prepare();
        exchange(handle);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = executor.submit(() -> success("complete", Map.of("handle", handle)));
            assertTrue(transferring.await(3, TimeUnit.SECONDS));
            var unrelated = executor.submit(() -> transactions.prepare(anonymous, "TEST",
                    URI.create("https://client.example.test/callback"), "another-state"));
            try {
                assertNotNull(unrelated.get(1, TimeUnit.SECONDS).get("handle"));
            } catch (TimeoutException error) {
                release.countDown();
                fail("An unrelated preparation waited for a live transfer");
            }
            CountDownLatch cancelStarted = new CountDownLatch(1);
            var cancellation = executor.submit(() -> {
                cancelStarted.countDown();
                return success("cancel", Map.of("handle", handle));
            });
            assertTrue(cancelStarted.await(3, TimeUnit.SECONDS));
            try {
                cancellation.get(100, TimeUnit.MILLISECONDS);
                fail("Cancellation acknowledged before the transfer finished");
            } catch (TimeoutException expected) {
                assertFalse(completion.isDone());
            } finally {
                release.countDown();
            }
            String token = completion.get(5, TimeUnit.SECONDS).getString("token");
            cancellation.get(5, TimeUnit.SECONDS);
            assertFalse(im.getProfileManager().tokenHasMoreUses(token));
            assertTrue(blocking.getSavedBags().isEmpty());
        } finally {
            release.countDown();
        }
    }

    public void testSeparateHandlesDoNotCancelEachOther() throws Exception {
        String first = prepare();
        String second = prepare();
        exchange(first);
        provider.identityBody = "{\"id\":\"second-account\",\"name\":\"Second\"}";
        exchange(second);
        success("cancel", Map.of("handle", first));
        Profile account = owner(success("complete", Map.of("handle", second)));
        assertEquals(List.of(employeeId), account.getSavedBags().get("guest").getContentsAsIds());
        assertEquals(409, call("complete", Map.of("handle", first)).status);
    }

    public void testExpiryRevokesUndeliveredTokensAndReclaimsCapacity() throws Exception {
        String first = prepare();
        String second = prepare();
        assertEquals(503, call("prepare", Map.of("provider", "TEST", "state", "state",
                "redirect_uri", "https://client.example.test/callback")).status);
        exchange(first);
        JSONObject result = success("complete", Map.of("handle", first));
        clock.now = clock.now.plusSeconds(300);
        transactions.expire();
        assertFalse(im.getProfileManager().tokenHasMoreUses(result.getString("token")));
        assertEquals(409, call("exchange", Map.of("handle", second,
                "code", OAuthHttpFixture.CODE)).status);
        assertNotNull(prepare());
    }

    public void testAcknowledgementKeepsDeliveredTokenAfterExpiry() throws Exception {
        String handle = prepare();
        exchange(handle);
        JSONObject result = success("complete", Map.of("handle", handle));
        success("acknowledge", Map.of("handle", handle));
        success("acknowledge", Map.of("handle", handle));
        assertEquals(409, call("complete", Map.of("handle", handle)).status);
        clock.now = clock.now.plusSeconds(300);
        transactions.expire();
        assertTrue(im.getProfileManager().tokenHasMoreUses(result.getString("token")));
    }

    public void testConcurrentExpiryKeepsAcknowledgedToken() throws Exception {
        String handle = prepare();
        exchange(handle);
        String token = success("complete", Map.of("handle", handle)).getString("token");
        success("acknowledge", Map.of("handle", handle));
        clock.now = clock.now.plusSeconds(300);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        clock.beforeRead = () -> {
            if (Thread.currentThread() == firstThread.get()) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Expiry barrier not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                firstThread.set(Thread.currentThread());
                transactions.expire();
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    transactions.expire();
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!second.isDone() && (secondThread.get() == null
                        || secondThread.get().getState() != Thread.State.BLOCKED)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertTrue(second.isDone() || (secondThread.get() != null
                        && secondThread.get().getState() == Thread.State.BLOCKED));
                release.countDown();
                first.get(3, TimeUnit.SECONDS);
                second.get(3, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        } finally {
            clock.beforeRead = () -> { };
        }
        assertTrue(im.getProfileManager().tokenHasMoreUses(token));
    }

    public void testInvalidCredentialNeverAppearsInResponseOrLogs() throws Exception {
        String sentinel = "unknown-credential-sentinel";
        OAuthHttpFixture.Browser http = request("logout", Map.of("format", "json"));
        http.headers.put("Authorization", "Token " + sentinel);
        new LogoutService(im).service(http.request, http.response);
        assertEquals(401, http.status);
        assertFalse(http.text().contains(sentinel));
        assertFalse(String.join("\n", logs).contains(sentinel));
    }

    public void testServerErrorDoesNotLogRequestCredentials() throws Exception {
        OAuthHttpFixture.Browser http = request("fixture", Map.of("format", "json",
                "token", anonymousToken, "password", "password-sentinel",
                "code", "authorization-code-sentinel", "state", "state-sentinel"));
        new JSONService(im) {
            @Override protected void execute() throws Exception {
                throw new Exception("Fixture failure");
            }
        }.service(http.request, http.response);
        assertEquals(500, http.status);
        String captured = http.text() + String.join("\n", logs);
        for (String secret : List.of(anonymousToken, "password-sentinel",
                "authorization-code-sentinel", "state-sentinel")) {
            assertFalse(captured.contains(secret));
        }
    }

    public void testDeliveredTokenCanBeRevokedAfterHandleExpiryWithoutAffectingNewLogin()
        throws Exception {
        String handle = prepare();
        exchange(handle);
        JSONObject result = success("complete", Map.of("handle", handle));
        Profile account = owner(result);
        String newer = im.getProfileManager().generate24hrKey(account);
        success("acknowledge", Map.of("handle", handle));
        clock.now = clock.now.plusSeconds(300);
        transactions.expire();
        success("revoke", Map.of("token", result.getString("token")));
        success("revoke", Map.of("token", result.getString("token")));
        assertFalse(im.getProfileManager().tokenHasMoreUses(result.getString("token")));
        assertEquals(account, im.getProfileManager().getPermission(newer, im.getClassKeys()).getProfile());
        String permanent = im.getProfileManager().generateApiKey(account);
        assertEquals(400, call("revoke", Map.of("token", permanent)).status);
        assertEquals(account.getUserId(), im.getProfileManager().getPermission(permanent,
                im.getClassKeys()).getProfile().getUserId());
    }

    public void testRevocationAcceptsHeaderAndConsumedSingleUseToken() throws Exception {
        String token = im.getProfileManager().generateSingleUseKey(anonymous);
        OAuthHttpFixture.Browser http = request("revoke", Map.of());
        http.headers.put("Authorization", "Token " + token);
        new TransactionService(im, transactions).service(http.request, http.response);
        assertEquals(http.text(), 200, http.status);
        assertFalse(im.getProfileManager().tokenHasMoreUses(token));
        success("revoke", Map.of("token", token));
        OAuthHttpFixture.Browser ambiguous = request("revoke", Map.of("token", anonymousToken));
        ambiguous.headers.put("Authorization", "Token " + anonymousToken);
        new TransactionService(im, transactions).service(ambiguous.request, ambiguous.response);
        assertEquals(400, ambiguous.status);
        assertTrue(im.getProfileManager().tokenHasMoreUses(anonymousToken));
    }

    public void testMalformedRequestsCannotExchangeOrLeakCredentials() throws Exception {
        String handle = prepare();
        assertEquals(400, call("exchange", Map.of("handle", handle)).status);
        OAuthHttpFixture.Browser duplicate = request("exchange", Map.of("handle", handle,
                "code", OAuthHttpFixture.CODE));
        duplicate.repeatedParameters.put("handle", new String[] {handle, "other-handle"});
        new TransactionService(im, transactions).service(duplicate.request, duplicate.response);
        assertEquals(400, duplicate.status);
        OAuthHttpFixture.Browser query = request("exchange", Map.of("handle", handle,
                "code", OAuthHttpFixture.CODE));
        query.queryString = "handle=" + handle;
        new TransactionService(im, transactions).service(query.request, query.response);
        assertEquals(400, query.status);
        assertEquals(409, call("complete", Map.of("handle", "unknown-handle")).status);
        OAuthHttpFixture.Browser invalid = call("prepare", Map.of("token", "invalid-token-sentinel",
                "provider", "TEST", "state", "state", "redirect_uri", "https://client.example.test"));
        assertEquals(401, invalid.status);
        assertTrue(provider.requests.isEmpty());
        provider.tokenStatus = 401;
        provider.tokenBody = "{\"error\":\"" + OAuthHttpFixture.TOKEN + "\"}";
        OAuthHttpFixture.Browser failed = call("exchange", Map.of("handle", handle,
                "code", OAuthHttpFixture.CODE));
        assertEquals(502, failed.status);
        String captured = String.join("\n", logs) + invalid.text() + failed.text() + duplicate.text();
        for (String secret : List.of(handle, anonymousToken, "invalid-token-sentinel",
                OAuthHttpFixture.SECRET, OAuthHttpFixture.CODE, OAuthHttpFixture.TOKEN)) {
            assertFalse("Secret present in an error or log", captured.contains(secret));
        }
    }

    public void testServletUsesApplicationLifecycleAndRestartRevokesUndeliveredToken()
        throws Exception {
        TransactionServlet servlet = new TransactionServlet();
        OAuthHttpFixture.Browser prepared = request("prepare", Map.of("provider", "TEST",
                "state", "browser-state", "redirect_uri", "https://client.example.test/callback"));
        servlet.doPost(prepared.request, prepared.response);
        assertEquals(prepared.text(), 200, prepared.status);
        String handle = new JSONObject(prepared.text()).getJSONObject("output").getString("handle");
        OAuthHttpFixture.Browser verified = request("exchange", Map.of("handle", handle,
                "code", OAuthHttpFixture.CODE));
        servlet.doPost(verified.request, verified.response);
        assertEquals(verified.text(), 200, verified.status);
        OAuthHttpFixture.Browser completed = request("complete", Map.of("handle", handle));
        servlet.doPost(completed.request, completed.response);
        assertEquals(completed.text(), 200, completed.status);
        String token = new JSONObject(completed.text()).getJSONObject("output").getString("token");
        assertTrue(im.getProfileManager().tokenHasMoreUses(token));
        InterMineContext.initialise(im, provider.properties("TEST"), new WebConfig(),
                new ClassResourceOpener(getClass()));
        assertFalse(im.getProfileManager().tokenHasMoreUses(token));
        OAuthHttpFixture.Browser replay = request("complete", Map.of("handle", handle));
        servlet.doPost(replay.request, replay.response);
        assertEquals(409, replay.status);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-21T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        private volatile Runnable beforeRead = () -> { };
        @Override public Instant instant() { beforeRead.run(); return now; }
    }
}
