package org.intermine.web.logic.profile;

import static org.easymock.EasyMock.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.intermine.api.InterMineAPITestCase;
import org.intermine.api.InterMineAPI;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.ProfileManager;
import org.intermine.web.context.InterMineContext;
import org.intermine.web.logic.ClassResourceOpener;
import org.intermine.web.logic.Constants;
import org.intermine.web.logic.config.WebConfig;
import org.intermine.web.logic.oauth2.OAuthClientSupport;
import org.intermine.web.logic.oauth2.OAuthExchangeException;
import org.intermine.web.struts.oauth2.Callback;
import org.intermine.web.struts.oauth2.Authenticator;
import org.intermine.web.struts.oauth2.DelegatedIdentity;
import org.intermine.webservice.server.oauth2.CallbackService;
import org.intermine.webservice.server.oauth2.AuthenticatorService;
import org.json.JSONObject;

public class OAuthExchangeIntegrationTest extends InterMineAPITestCase {
    private OAuthHttpFixture provider;
    private Properties properties;
    private final List<String> logs = new ArrayList<>();
    private Level oldLevel;
    private AppenderSkeleton appender;

    public OAuthExchangeIntegrationTest(String name) { super(name); }

    @Override public void setUp() throws Exception {
        super.setUp();
        provider = new OAuthHttpFixture();
        properties = provider.properties("TEST");
        initialise();
        Logger root = Logger.getRootLogger();
        oldLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender = new AppenderSkeleton() {
            @Override protected void append(LoggingEvent event) {
                logs.add(event.getRenderedMessage());
                if (event.getThrowableStrRep() != null) {
                    logs.add(String.join("\n", event.getThrowableStrRep()));
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
        provider.close();
        super.tearDown();
    }

    private void initialise() {
        InterMineContext.initialise(im, properties, new WebConfig(),
                new ClassResourceOpener(getClass()));
    }

    private OAuthHttpFixture.Browser browser() throws Exception {
        OAuthHttpFixture.Browser http = new OAuthHttpFixture.Browser();
        http.contextValues.put(Constants.INTERMINE_API, im);
        http.sessionValues.put(Constants.PROFILE, im.getProfileManager().createAnonymousProfile());
        return http;
    }

    private ActionForward callback(OAuthHttpFixture.Browser http) {
        ActionMapping mapping = new ActionMapping();
        mapping.addForwardConfig(new ActionForward("login", "/login", false));
        mapping.addForwardConfig(new ActionForward("mymine", "/mymine", false));
        return new Callback().execute(mapping, null, http.request, http.response);
    }

    public void testBrowserAndRestUseSameProviderIdentityWithoutRestSessionLogin() throws Exception {
        OAuthHttpFixture.Browser web = browser();
        assertEquals("mymine", callback(web).getName());
        Profile profile = (Profile) web.sessionValues.get(Constants.PROFILE);
        assertTrue(profile.isLoggedIn());
        assertEquals(1, web.rotations);
        OAuthHttpFixture.Browser rest = browser();
        Object previous = rest.sessionValues.get(Constants.PROFILE);
        new CallbackService(im).service(rest.request, rest.response);
        assertEquals(rest.text(), 200, rest.status);
        JSONObject output = new JSONObject(rest.text()).getJSONObject("output");
        String key = output.getString("token");
        assertEquals(profile.getUserId(), im.getProfileManager().getPermission(key, im.getClassKeys()).getProfile().getUserId());
        assertSame(previous, rest.sessionValues.get(Constants.PROFILE));
        assertEquals(0, rest.rotations);
    }

    public void testRestProviderFailuresReturnErrorsWithoutCreatingProfiles() throws Exception {
        provider.tokenStatus = 401;
        provider.tokenBody = "{\"error\":\"invalid_grant\",\"error_description\":\"" + OAuthHttpFixture.TOKEN + "\"}";
        int count = im.getProfileManager().getProfileUserNames().size();
        OAuthHttpFixture.Browser rest = browser();
        new CallbackService(im).service(rest.request, rest.response);
        assertTrue("Provider failure returned " + rest.status + ": " + rest.text(), rest.status >= 400);
        assertEquals(count, im.getProfileManager().getProfileUserNames().size());
        assertFalse(rest.text().contains(OAuthHttpFixture.TOKEN));
    }

    public void testInvalidIdentityValuesCannotCreateBrowserAccounts() throws Exception {
        for (String value : new String[] {"null", "\"\"", "\"   \"", "{}", "[]", "true"}) {
            provider.identityBody = "{\"id\":" + value + ",\"name\":\"Invalid Identity\"}";
            int count = im.getProfileManager().getProfileUserNames().size();
            OAuthHttpFixture.Browser web = browser();
            Object previous = web.sessionValues.get(Constants.PROFILE);
            assertEquals("ID=" + value, "login", callback(web).getName());
            assertSame(previous, web.sessionValues.get(Constants.PROFILE));
            assertEquals(count, im.getProfileManager().getProfileUserNames().size());
        }
    }

    public void testIdentityErrorInSuccessfulHttpResponseCannotAuthenticate() throws Exception {
        provider.identityBody = "{\"error\":\"denied\",\"id\":\"untrusted\",\"name\":\"Denied\"}";
        OAuthHttpFixture.Browser web = browser();
        assertEquals("login", callback(web).getName());
        assertFalse(((Profile) web.sessionValues.get(Constants.PROFILE)).isLoggedIn());
    }

    public void testHttpIdentityFailureCannotAuthenticate() throws Exception {
        provider.identityStatus = 500;
        OAuthHttpFixture.Browser web = browser();
        assertEquals("login", callback(web).getName());
        assertFalse(((Profile) web.sessionValues.get(Constants.PROFILE)).isLoggedIn());
    }

    public void testExchangeDoesNotLogCredentialsOrProviderPayloads() throws Exception {
        OAuthHttpFixture.Browser web = browser();
        callback(web);
        String captured = String.join("\n", logs);
        for (String secret : new String[] {"secret-sentinel", "code-sentinel", OAuthHttpFixture.TOKEN}) {
            assertFalse("OAuth logs contain " + secret, captured.contains(secret));
        }
    }

    public void testMalformedTokenAndIdentityResponsesFailRestExplicitly() throws Exception {
        provider.tokenBody = "not-json-" + OAuthHttpFixture.TOKEN;
        OAuthHttpFixture.Browser rest = browser();
        new CallbackService(im).service(rest.request, rest.response);
        assertTrue("Malformed token returned success", rest.status >= 400);
        provider.tokenBody = "{\"access_token\":\"" + OAuthHttpFixture.TOKEN + "\"}";
        provider.identityBody = "not-json-" + OAuthHttpFixture.TOKEN;
        rest = browser();
        new CallbackService(im).service(rest.request, rest.response);
        assertTrue("Malformed identity returned success", rest.status >= 400);
        assertFalse(rest.text().contains(OAuthHttpFixture.TOKEN));
    }

    public void testWireFormatsPreserveValuesAndConfiguredTokenPlacement() throws Exception {
        for (String message : new String[] {"BODY", "QUERY"}) {
            for (String format : new String[] {"FORM", "JSON"}) {
                for (String resource : new String[] {"header", "queryparam", "body"}) {
                    provider.requests.clear();
                    properties.setProperty("oauth2.TEST.messageformat", message);
                    properties.setProperty("oauth2.TEST.responsetype", format);
                    properties.setProperty("oauth2.TEST.resource-auth-mechanism", resource);
                    provider.tokenContentType = format.equals("FORM") ? "application/x-www-form-urlencoded" : "application/json";
                    provider.tokenBody = format.equals("FORM") ? "access_token=" + OAuthHttpFixture.TOKEN + "&expires_in=3600"
                            : "{\"access_token\":\"" + OAuthHttpFixture.TOKEN + "\",\"expires_in\":3600}";
                    OAuthHttpFixture.Browser web = browser();
                    assertEquals(message + "/" + format + "/" + resource, "mymine", callback(web).getName());
                    OAuthHttpFixture.WireRequest token = provider.requests.get(0);
                    OAuthHttpFixture.WireRequest identity = provider.requests.get(1);
                    assertEquals("POST", token.method);
                    Map<String, String> tokenQuery = parameters(token.uri.getRawQuery());
                    assertEquals("existing", tokenQuery.get("tenant"));
                    Map<String, String> credentials = message.equals("BODY") ? parameters(token.body) : tokenQuery;
                    assertEquals(OAuthHttpFixture.CODE, credentials.get("code"));
                    assertEquals(OAuthHttpFixture.SECRET, credentials.get("client_secret"));
                    assertEquals("client +&雪", credentials.get("client_id"));
                    assertEquals("http://mine.example.test/mine/oauth2callback.do?provider=TEST", credentials.get("redirect_uri"));
                    assertEquals("authorization_code", credentials.get("grant_type"));
                    if (message.equals("BODY")) { assertFalse(tokenQuery.containsKey("client_secret")); }
                    else { assertEquals("", token.body); }
                    Map<String, String> identityQuery = parameters(identity.uri.getRawQuery());
                    assertEquals("existing", identityQuery.get("tenant"));
                    assertEquals(resource.equals("body") ? "POST" : "GET", identity.method);
                    if (resource.equals("header")) {
                        assertEquals("Bearer " + OAuthHttpFixture.TOKEN, identity.authorization);
                        assertFalse(identityQuery.containsKey("access_token"));
                        assertEquals("", identity.body);
                    } else if (resource.equals("queryparam")) {
                        assertNull(identity.authorization);
                        assertEquals(OAuthHttpFixture.TOKEN, identityQuery.get("access_token"));
                        assertEquals("", identity.body);
                    } else {
                        assertNull(identity.authorization);
                        assertFalse(identityQuery.containsKey("access_token"));
                        assertEquals(OAuthHttpFixture.TOKEN, parameters(identity.body).get("access_token"));
                    }
                }
            }
        }
    }

    public void testTokenErrorOrUnsupportedTypeCannotAuthenticate() throws Exception {
        for (String token : new String[] {
            "{\"access_token\":\"\"}", "{\"access_token\":\"   \"}",
            "{\"access_token\":\"token\",\"token_type\":\"DPoP\"}",
            "{\"access_token\":\"token\",\"error\":\"invalid_grant\"}"}) {
            provider.tokenBody = token;
            OAuthHttpFixture.Browser web = browser();
            assertEquals("Token=" + token, "login", callback(web).getName());
        }
    }

    public void testDuplicateFormTokenIsRejected() throws Exception {
        properties.setProperty("oauth2.TEST.responsetype", "FORM");
        provider.tokenContentType = "application/x-www-form-urlencoded";
        provider.tokenBody = "access_token=first&access_token=second";
        assertEquals("login", callback(browser()).getName());
    }

    public void testIdentityRedirectDoesNotForwardBearerCredentials() throws Exception {
        provider.identityStatus = 302;
        provider.identityRedirect = provider.uri("/redirected").toString();
        assertEquals("login", callback(browser()).getName());
        assertEquals("Redirect destination must receive no request", 2, provider.requests.size());
    }

    public void testGoogleUsesUserInfoWithoutDecodingIrrelevantIdToken() throws Exception {
        properties = provider.properties("GOOGLE");
        initialise();
        provider.tokenBody = "{\"access_token\":\"" + OAuthHttpFixture.TOKEN + "\",\"id_token\":\"e30.bm90LWpzb24.signature\"}";
        OAuthHttpFixture.Browser web = browser();
        web.parameters.put("provider", "GOOGLE");
        assertEquals("mymine", callback(web).getName());
        assertTrue(((Profile) web.sessionValues.get(Constants.PROFILE)).isLoggedIn());
    }

    public void testProviderEmailDoesNotSelectAnExistingLocalAccount() throws Exception {
        Profile existing = im.getProfileManager().createNewProfile("provider@example.test", "password");
        OAuthHttpFixture.Browser web = browser();
        assertEquals("mymine", callback(web).getName());
        Profile delegated = (Profile) web.sessionValues.get(Constants.PROFILE);
        assertFalse(existing.getUserId().equals(delegated.getUserId()));
    }

    public void testConfigurationFailureDoesNotLogSensitiveExceptionCause() throws Exception {
        properties.setProperty("oauth2.TEST.url.token", "invalid:" + OAuthHttpFixture.TOKEN);
        OAuthHttpFixture.Browser rest = browser();
        new CallbackService(im).service(rest.request, rest.response);
        assertTrue("Invalid configuration returned success", rest.status >= 400);
        assertFalse(rest.text().contains(OAuthHttpFixture.TOKEN));
        assertFalse(String.join("\n", logs).contains(OAuthHttpFixture.TOKEN));
    }

    public void testReadTimeoutBoundsAStalledProvider() throws Exception {
        provider.blockToken = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = worker.submit(() -> new OAuthClientSupport(properties, 1000, 50)
                    .exchange("TEST", OAuthHttpFixture.CODE, URI.create("http://client.test/callback")));
            assertTrue(provider.tokenArrived.await(2, TimeUnit.SECONDS));
            try {
                result.get(2, TimeUnit.SECONDS);
                fail("A stalled provider must fail the exchange");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof OAuthExchangeException);
                assertTrue(expected.getCause().getCause() instanceof SocketTimeoutException);
            } catch (TimeoutException unbounded) {
                fail("Configured read timeout did not bound the exchange");
            }
        } finally {
            provider.blockToken.countDown();
            worker.shutdownNow();
        }
    }

    public void testUnexpectedProfileFailureDoesNotExposeNestedSecrets() throws Exception {
        InterMineAPI brokenApi = createNiceMock(InterMineAPI.class);
        ProfileManager brokenProfiles = createNiceMock(ProfileManager.class);
        expect(brokenApi.getBagManager()).andStubReturn(im.getBagManager());
        expect(brokenApi.getObjectStore()).andStubReturn(im.getObjectStore());
        expect(brokenApi.getClassKeys()).andStubReturn(im.getClassKeys());
        expect(brokenApi.getProfileManager()).andStubReturn(brokenProfiles);
        expect(brokenProfiles.grantPermission(eq("TEST"), eq("provider-123"), anyObject()))
                .andThrow(new IllegalStateException("Failure " + OAuthHttpFixture.TOKEN,
                    new IllegalArgumentException(OAuthHttpFixture.SECRET)));
        replay(brokenApi, brokenProfiles);
        OAuthHttpFixture.Browser rest = browser();
        new CallbackService(brokenApi).service(rest.request, rest.response);
        assertEquals(2, provider.requests.size());
        assertEquals(rest.text(), 502, rest.status);
        String visible = rest.text() + String.join("\n", logs);
        assertFalse(visible.contains(OAuthHttpFixture.TOKEN));
        assertFalse(visible.contains("secret-sentinel"));
        assertFalse(visible.contains("code-sentinel"));
    }

    public void testAuthorizationLinkPreservesEndpointQueryAndEncodedProvider() throws Exception {
        String providerName = "TEST &雪";
        properties = provider.properties(providerName);
        initialise();
        OAuthHttpFixture.Browser web = browser();
        web.parameters.put("provider", providerName);
        assertNull(new Authenticator().execute(new ActionMapping(), null, web.request, web.response));
        Map<String, String> query = parameters(URI.create(web.redirect).getRawQuery());
        assertEquals("existing", query.get("tenant"));
        assertEquals("client +&雪", query.get("client_id"));
        assertEquals("profile email", query.get("scope"));
        assertEquals("code", query.get("response_type"));
        assertEquals(providerName, parameters(URI.create(query.get("redirect_uri")).getRawQuery()).get("provider"));
        assertNotNull(query.get("state"));
        assertFalse(query.containsKey("client_secret"));
        assertFalse(query.containsKey("code"));
    }

    public void testRestLinkLeavesCorrelationToClientAndRequiresClientId() throws Exception {
        OAuthHttpFixture.Browser rest = browser();
        new AuthenticatorService(im).service(rest.request, rest.response);
        assertEquals(rest.text(), 200, rest.status);
        Map<String, String> query = parameters(URI.create(new JSONObject(rest.text()).getString("link")).getRawQuery());
        assertFalse(query.containsKey("state"));
        assertFalse(query.containsKey("redirect_uri"));
        properties.remove("oauth2.TEST.client-id");
        rest = browser();
        new AuthenticatorService(im).service(rest.request, rest.response);
        assertEquals(rest.text(), 400, rest.status);
    }

    public void testConfiguredIdentityMappingPreservesNumericIdAndNameParts() {
        properties.setProperty("oauth2.TEST.identity-envelope", "account");
        properties.setProperty("oauth2.TEST.id-key", "subject");
        properties.setProperty("oauth2.TEST.name-key", "first,last");
        provider.identityBody = "{\"account\":{\"subject\":123,\"first\":\"Ada\",\"last\":\"Lovelace\","
                + "\"emails\":{\"account\":\"ada@example.test\"}}}";
        DelegatedIdentity identity = new OAuthClientSupport(properties).exchange("TEST", OAuthHttpFixture.CODE,
                URI.create("http://client.test/callback"));
        assertEquals("123", identity.getId());
        assertEquals("Ada Lovelace", identity.getName());
        assertEquals("ada@example.test", identity.getEmail());
        assertEquals("TEST", identity.getProvider());
    }

    private static Map<String, String> parameters(String encoded) {
        Map<String, String> result = new HashMap<>();
        if (encoded != null && !encoded.isEmpty()) {
            for (String pair : encoded.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                assertFalse("Duplicate wire parameter: " + key, result.containsKey(key));
                result.put(key, parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
        }
        return result;
    }
}
