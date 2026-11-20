package org.intermine.web.logic.profile;

import static org.junit.Assert.*;

import org.intermine.api.profile.Profile;
import org.intermine.web.logic.Constants;
import org.intermine.web.logic.session.SessionMethods;
import org.junit.Test;

public class SessionAuthorityTest {
    @Test
    public void staleMarkerCannotGrantAuthority() throws Exception {
        HttpTestSupport http = new HttpTestSupport();
        http.attributes.put(Constants.PROFILE, HttpTestSupport.profile(false));
        http.attributes.put(Constants.IS_SUPERUSER, true);
        assertFalse(SessionMethods.isSuperUser(http.session));
    }

    @Test
    public void currentAdministratorDoesNotNeedMarker() throws Exception {
        HttpTestSupport http = new HttpTestSupport();
        http.attributes.put(Constants.PROFILE, HttpTestSupport.profile(true));
        assertTrue(SessionMethods.isSuperUser(http.session));
    }

    @Test
    public void missingProfileCannotUseMarker() throws Exception {
        HttpTestSupport http = new HttpTestSupport();
        http.attributes.put(Constants.IS_SUPERUSER, true);
        assertFalse(SessionMethods.isSuperUser(http.session));
    }

    @Test
    public void settingOrdinaryProfileClearsAdministratorMarker() throws Exception {
        HttpTestSupport http = new HttpTestSupport();
        http.attributes.put(Constants.IS_SUPERUSER, true);
        Profile ordinary = HttpTestSupport.profile(false);
        SessionMethods.setProfile(http.session, ordinary);
        assertSame(ordinary, http.attributes.get(Constants.PROFILE));
        assertEquals(Boolean.FALSE, http.attributes.get(Constants.IS_SUPERUSER));
    }

    @Test
    public void clearingProfileClearsAdministratorMarker() throws Exception {
        HttpTestSupport http = new HttpTestSupport();
        http.attributes.put(Constants.IS_SUPERUSER, true);
        SessionMethods.setProfile(http.session, null);
        assertEquals(Boolean.FALSE, http.attributes.get(Constants.IS_SUPERUSER));
    }
}
