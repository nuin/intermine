package org.intermine.web.logic.profile;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.struts.Globals;
import org.intermine.api.InterMineAPITestCase;
import org.intermine.api.profile.InterMineBag;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.SavedQuery;
import org.intermine.web.logic.Constants;
import org.intermine.web.logic.results.WebState;
import org.intermine.web.logic.session.SessionMethods;

public class BrowserLoginTest extends InterMineAPITestCase {
    private HttpTestSupport http;
    private Profile source;
    private Profile account;

    public BrowserLoginTest(String name) {
        super(name);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        http = new HttpTestSupport();
        source = im.getProfileManager().createAnonymousProfile();
        account = im.getProfileManager().createNewProfile("browser-destination", "password");
        http.contextAttributes.put(Constants.INTERMINE_API, im);
        http.attributes.put(Constants.PROFILE, source);
    }

    public void testSuccessfulPasswordLoginRotatesAndClearsOldAdminState() {
        WebState state = new WebState();
        state.addSubtab("subtabmymine", "users");
        state.setState("preserved", "value");
        http.attributes.put(Constants.WEB_STATE, state);
        http.attributes.put("ADMIN_CSRF_TOKEN", "previous-identity-token");
        http.attributes.put(Globals.TRANSACTION_TOKEN_KEY, "query-builder-token");
        LoginHandler.doStaticLogin(http.request, account.getUsername(), "password");
        assertEquals(1, http.rotations);
        assertEquals(account.getUserId(), SessionMethods.getProfile(http.session).getUserId());
        assertEquals(account.getUsername(), SessionMethods.getProfile(http.session).getUsername());
        assertNull(http.attributes.get("ADMIN_CSRF_TOKEN"));
        assertEquals("query-builder-token", http.attributes.get(Globals.TRANSACTION_TOKEN_KEY));
        assertEquals("lists", state.getSubtab("subtabmymine"));
        assertEquals("value", state.getState("preserved"));
    }

    public void testAuthenticatedCompletionUsedByOAuthRotatesAndInstallsProfile() {
        LoginHandler.completeLogin(http.request, account);
        assertEquals(1, http.rotations);
        assertSame(account, http.attributes.get(Constants.PROFILE));
    }

    public void testCompletionRejectsAnonymousAndMissingProfiles() {
        for (Profile invalid : new Profile[] {null, source}) {
            try {
                LoginHandler.completeLogin(http.request, invalid);
                fail("Completion requires an authenticated persisted profile");
            } catch (IllegalArgumentException expected) {
                assertSame(source, http.attributes.get(Constants.PROFILE));
                assertEquals(0, http.rotations);
            }
        }
    }

    public void testInvalidCredentialsLeaveAnonymousSessionIntact() {
        try {
            LoginHandler.doStaticLogin(http.request, account.getUsername(), "wrong-password");
            fail("Incorrect credentials must not authenticate");
        } catch (RuntimeException expected) {
            assertSame(source, http.attributes.get(Constants.PROFILE));
            assertEquals(0, http.rotations);
        }
    }

    public void testMergeFailureDoesNotInstallAuthenticatedProfile() {
        IllegalStateException failure = new IllegalStateException("injected history failure");
        AtomicBoolean failReads = new AtomicBoolean(false);
        Profile failingSource = new Profile(im.getProfileManager(), null, null, null,
                Profile.NO_QUERIES, Collections.<String, InterMineBag>emptyMap(),
                Profile.NO_TEMPLATES, null, true, false) {
            @Override
            public Map<String, SavedQuery> getHistory() {
                if (failReads.get()) {
                    throw failure;
                }
                return super.getHistory();
            }
        };
        failReads.set(true);
        http.attributes.put(Constants.PROFILE, failingSource);
        try {
            LoginHandler.doStaticLogin(http.request, account.getUsername(), "password");
            fail("A failed merge must not authenticate the browser");
        } catch (IllegalStateException expected) {
            assertSame(failure, expected);
            assertSame(failingSource, http.attributes.get(Constants.PROFILE));
            assertEquals(0, http.rotations);
        }
    }
}
