package org.intermine.web.logic.profile;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.struts.Globals;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;
import org.apache.struts.action.ActionMessages;
import org.intermine.api.InterMineAPITestCase;
import org.intermine.api.profile.Profile;
import org.intermine.web.context.InterMineContext;
import org.intermine.web.logic.ClassResourceOpener;
import org.intermine.web.logic.Constants;
import org.intermine.web.logic.config.WebConfig;
import org.intermine.web.logic.session.SessionMethods;
import org.intermine.web.struts.LogoutAction;
import org.intermine.web.struts.oauth2.Authenticator;
import org.intermine.web.struts.oauth2.Callback;

public class OAuthBrowserFlowTest extends InterMineAPITestCase {
    private OAuthHttpFixture provider;
    private Properties properties;
    private OAuthHttpFixture.Browser browser;
    private ActionMapping mapping;

    public OAuthBrowserFlowTest(String name) { super(name); }

    @Override public void setUp() throws Exception {
        super.setUp();
        provider = new OAuthHttpFixture();
        properties = provider.properties("TEST");
        properties.putAll(provider.properties("OTHER"));
        InterMineContext.initialise(im, properties, new WebConfig(), new ClassResourceOpener(getClass()));
        browser = new OAuthHttpFixture.Browser();
        browser.contextValues.put(Constants.INTERMINE_API, im);
        browser.sessionValues.put(Constants.PROFILE, im.getProfileManager().createAnonymousProfile());
        mapping = new ActionMapping();
        for (String name : new String[] {"login", "mymine", "begin"}) {
            mapping.addForwardConfig(new ActionForward(name, "/" + name, false));
        }
    }

    @Override public void tearDown() throws Exception {
        provider.close();
        super.tearDown();
    }

    private String begin(OAuthHttpFixture.Browser http, String name) throws Exception {
        http.parameters.put("provider", name);
        assertNull(new Authenticator().execute(mapping, null, http.request, http.response));
        for (String pair : URI.create(http.redirect).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals("state")) {
                String state = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                http.parameters.put("state", state);
                return state;
            }
        }
        throw new AssertionError("No state in authorization redirect");
    }

    private ActionForward callback(OAuthHttpFixture.Browser http) {
        return new Callback().execute(mapping, null, http.request, http.response);
    }

    public void testMissingAndWrongStateDoNotExchangeOrConsumeValidFlow() throws Exception {
        String state = begin(browser, "TEST");
        browser.parameters.remove("state");
        assertEquals("login", callback(browser).getName());
        browser.parameters.put("state", "wrong");
        assertEquals("login", callback(browser).getName());
        assertTrue(provider.requests.isEmpty());
        browser.parameters.put("state", state);
        assertEquals("mymine", callback(browser).getName());
    }

    public void testStateCannotBeReusedForAnotherProvider() throws Exception {
        begin(browser, "TEST");
        browser.parameters.put("provider", "OTHER");
        assertEquals("login", callback(browser).getName());
        assertTrue(provider.requests.isEmpty());
        assertFalse(SessionMethods.getProfile(browser.request.getSession()).isLoggedIn());
    }

    public void testSuccessfulCallbackCannotReplayItsState() throws Exception {
        begin(browser, "TEST");
        assertEquals("mymine", callback(browser).getName());
        int count = provider.requests.size();
        Profile profile = SessionMethods.getProfile(browser.request.getSession());
        assertEquals("login", callback(browser).getName());
        assertEquals(count, provider.requests.size());
        assertSame(profile, SessionMethods.getProfile(browser.request.getSession()));
    }

    public void testFailedExchangeStillConsumesTheClaimedState() throws Exception {
        begin(browser, "TEST");
        provider.tokenStatus = 401;
        assertEquals("login", callback(browser).getName());
        int count = provider.requests.size();
        provider.tokenStatus = 200;
        assertEquals("login", callback(browser).getName());
        assertEquals(count, provider.requests.size());
    }

    public void testDelayedCallbackCannotReplaceNewerPasswordLogin() throws Exception {
        Profile account = im.getProfileManager().createNewProfile("newer-login", "password");
        runDelayedCallback(() -> {
            OAuthHttpFixture.Browser password = new OAuthHttpFixture.Browser(browser);
            LoginHandler.doStaticLogin(password.request, account.getUsername(), "password");
        });
        assertEquals(account.getUserId(), SessionMethods.getProfile(browser.request.getSession()).getUserId());
    }

    public void testDelayedCallbackCannotCompleteAfterNewAuthorizationStarts() throws Exception {
        runDelayedCallback(() -> begin(new OAuthHttpFixture.Browser(browser), "OTHER"));
        assertFalse(SessionMethods.getProfile(browser.request.getSession()).isLoggedIn());
    }

    public void testDelayedCallbackCannotCreateAccountOrLoginAfterLogout() throws Exception {
        runDelayedCallback(() -> {
            OAuthHttpFixture.Browser logout = new OAuthHttpFixture.Browser(browser);
            new LogoutAction().execute(mapping, null, logout.request, logout.response);
        });
        assertNull(SessionMethods.getProfile(browser.request.getSession()));
    }

    private void runDelayedCallback(Transition newer) throws Exception {
        begin(browser, "TEST");
        int profiles = im.getProfileManager().getProfileUserNames().size();
        provider.blockToken = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<ActionForward> result = worker.submit(() -> callback(browser));
            assertTrue(provider.tokenArrived.await(2, TimeUnit.SECONDS));
            newer.run();
            provider.blockToken.countDown();
            assertEquals("login", result.get(2, TimeUnit.SECONDS).getName());
            assertEquals("The delayed exchange must reach userinfo successfully", 2, provider.requests.size());
            assertEquals("A stale callback must not create or update an account", profiles,
                    im.getProfileManager().getProfileUserNames().size());
        } finally {
            provider.blockToken.countDown();
            worker.shutdownNow();
        }
    }

    public void testIdentityDisplayNamesAreEscapedOnlyAtMessageRendering() throws Exception {
        String providerName = "<em>Provider</em>";
        String name = "<em>Person</em> & \"quoted\"";
        properties.putAll(provider.properties(providerName));
        provider.identityBody = "{\"id\":\"quoted-person\",\"name\":\"<em>Person</em> & \\\"quoted\\\"\"}";
        begin(browser, providerName);
        assertEquals("mymine", callback(browser).getName());
        Profile profile = SessionMethods.getProfile(browser.request.getSession());
        assertEquals(name, profile.getPreferences().get("aka"));
        ActionMessages messages = (ActionMessages) browser.requestValues.get(Globals.MESSAGE_KEY);
        Iterator<?> iterator = messages.get();
        ActionMessage message = (ActionMessage) iterator.next();
        assertEquals("&lt;em&gt;Provider&lt;/em&gt;", message.getValues()[0]);
        assertEquals("&lt;em&gt;Person&lt;/em&gt; &amp; &quot;quoted&quot;", message.getValues()[1]);
    }

    private interface Transition { void run() throws Exception; }
}
