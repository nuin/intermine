package org.intermine.web.logic.profile;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import org.apache.struts.Globals;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.intermine.api.InterMineAPI;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.ProfileManager;
import org.intermine.web.logic.Constants;
import org.intermine.web.struts.EditPropertiesAction;
import org.intermine.web.struts.EditPropertiesForm;
import org.intermine.web.struts.ModifySuperUserAction;
import org.intermine.web.struts.ModifySuperUserForm;
import org.intermine.web.struts.UsersController;
import org.intermine.web.struts.ShowPropertiesController;
import org.junit.Test;

public class AdministrativeAuthorizationTest {
    private HttpTestSupport ordinary() throws Exception {
        HttpTestSupport http = new HttpTestSupport();
        http.attributes.put(Constants.PROFILE, HttpTestSupport.profile(false));
        http.attributes.put(Constants.IS_SUPERUSER, true);
        return http;
    }

    private HttpTestSupport administrator() throws Exception {
        HttpTestSupport http = new HttpTestSupport();
        http.attributes.put(Constants.PROFILE, HttpTestSupport.profile(true));
        http.attributes.put("ADMIN_CSRF_TOKEN", "expected-admin-token");
        http.parameters.put("adminToken", "expected-admin-token");
        Properties properties = new Properties();
        properties.setProperty("fixture", "before");
        http.contextAttributes.put(Constants.WEB_PROPERTIES, properties);
        http.contextAttributes.put(Constants.PROPERTIES_ORIGINS, new HashMap<String, List<String>>());
        return http;
    }

    private ActionMapping mapping() {
        ActionMapping mapping = new ActionMapping();
        mapping.addForwardConfig(new ActionForward("success", "/showProperties.do", false));
        mapping.addForwardConfig(new ActionForward("failure", "/begin.do", false));
        mapping.addForwardConfig(new ActionForward("mymine", "/mymine.do", false));
        return mapping;
    }

    private ActionForward edit(HttpTestSupport http, String value) throws Exception {
        EditPropertiesForm form = new EditPropertiesForm();
        form.setPropertyName("fixture");
        form.setPropertyValue(value);
        return new EditPropertiesAction().execute(mapping(), form, http.request, http.response);
    }

    private String property(HttpTestSupport http) {
        return ((Properties) http.contextAttributes.get(Constants.WEB_PROPERTIES))
                .getProperty("fixture");
    }

    @Test
    public void ordinaryRoleUpdateIsRejectedBeforeProfileManagerAccess() throws Exception {
        HttpTestSupport http = ordinary();
        assertNull(new ModifySuperUserAction().execute(mapping(), new ModifySuperUserForm(),
                http.request, http.response));
        assertEquals(403, http.status);
    }

    @Test
    public void missingProfileCannotUpdateRoles() throws Exception {
        HttpTestSupport http = new HttpTestSupport();
        assertNull(new ModifySuperUserAction().execute(mapping(), new ModifySuperUserForm(),
                http.request, http.response));
        assertEquals(403, http.status);
    }

    @Test
    public void ordinaryUserEnumerationIsRejectedBeforeProfileManagerAccess() throws Exception {
        HttpTestSupport http = ordinary();
        assertNull(new UsersController().execute(null, mapping(), new ModifySuperUserForm(),
                http.request, http.response));
        assertEquals(403, http.status);
    }

    @Test
    public void directUsersActionRequiresAnAdministrator() throws Exception {
        HttpTestSupport http = ordinary();
        assertNull(new UsersController().execute(mapping(), new ModifySuperUserForm(),
                http.request, http.response));
        assertEquals(403, http.status);
    }

    @Test
    public void ordinaryPropertyUpdateIsForbidden() throws Exception {
        HttpTestSupport http = ordinary();
        assertNull(edit(http, "forbidden"));
        assertEquals(403, http.status);
    }

    @Test
    public void propertyUpdateNeedsMatchingToken() throws Exception {
        for (String token : new String[] {null, "wrong-token"}) {
            HttpTestSupport http = administrator();
            http.parameters.put("adminToken", token);
            assertNull(edit(http, "forbidden"));
            assertEquals(403, http.status);
            assertEquals("before", property(http));
        }
    }

    @Test
    public void getCannotChangeProperties() throws Exception {
        HttpTestSupport http = administrator();
        http.method = "GET";
        assertNull(edit(http, "forbidden"));
        assertEquals(405, http.status);
        assertEquals("POST", http.headers.get("Allow"));
        assertEquals("before", property(http));
    }

    @Test
    public void validTokenIsSingleUseAndPreservesQueryBuilderToken() throws Exception {
        HttpTestSupport http = administrator();
        http.attributes.put(Globals.TRANSACTION_TOKEN_KEY, "query-builder-token");
        assertEquals("/showProperties.do", edit(http, "first").getPath());
        assertEquals("first", property(http));
        assertNull(http.attributes.get("ADMIN_CSRF_TOKEN"));
        assertEquals("query-builder-token", http.attributes.get(Globals.TRANSACTION_TOKEN_KEY));
        assertNull(edit(http, "second"));
        assertEquals(403, http.status);
        assertEquals("first", property(http));
    }

    @Test
    public void roleUpdateRequiresTokenBeforeProfileManagerAccess() throws Exception {
        HttpTestSupport http = administrator();
        http.parameters.clear();
        assertNull(new ModifySuperUserAction().execute(mapping(), new ModifySuperUserForm(),
                http.request, http.response));
        assertEquals(403, http.status);
    }

    @Test
    public void authorizedRoleFormRetainsExistingAdministrators() throws Exception {
        HttpTestSupport http = administrator();
        Profile admin = (Profile) http.attributes.get(Constants.PROFILE);
        InterMineAPI api = createMock(InterMineAPI.class);
        ProfileManager manager = createMock(ProfileManager.class);
        expect(api.getProfileManager()).andReturn(manager);
        expect(manager.getProfileUserNames()).andReturn(Arrays.asList("admin"));
        expect(manager.getSuperuser()).andReturn("admin");
        expect(manager.getProfile("admin")).andReturn(admin);
        replay(api, manager);
        http.contextAttributes.put(Constants.INTERMINE_API, api);
        ModifySuperUserForm form = new ModifySuperUserForm();
        form.setSuperUsers(new String[] {"admin"});
        ActionForward result = new ModifySuperUserAction().execute(mapping(), form,
                http.request, http.response);
        assertEquals("/mymine.do", result.getPath());
        assertNull(http.attributes.get("ADMIN_CSRF_TOKEN"));
        verify(api, manager);
    }

    @Test
    public void authorizedUsersViewIssuesAnAdministratorToken() throws Exception {
        HttpTestSupport http = administrator();
        http.attributes.remove("ADMIN_CSRF_TOKEN");
        InterMineAPI api = createMock(InterMineAPI.class);
        ProfileManager manager = createMock(ProfileManager.class);
        expect(api.getProfileManager()).andReturn(manager);
        expect(manager.getSuperUsers()).andReturn(Arrays.asList("admin"));
        expect(manager.getProfileUserNames()).andReturn(Arrays.asList("admin", "ordinary"));
        replay(api, manager);
        http.contextAttributes.put(Constants.INTERMINE_API, api);
        new UsersController().execute(null, mapping(), new ModifySuperUserForm(),
                http.request, http.response);
        assertNotNull(http.attributes.get("ADMIN_CSRF_TOKEN"));
        assertEquals(Arrays.asList("admin", "ordinary"), http.requestAttributes.get("users"));
        verify(api, manager);
    }
    @Test
    public void propertiesViewRequiresCurrentAdministrator() throws Exception {
        HttpTestSupport http = ordinary();
        assertNull(new ShowPropertiesController().execute(mapping(), null,
                http.request, http.response));
        assertEquals(403, http.status);
        assertNull(http.attributes.get(AdminAccess.TOKEN_ATTRIBUTE));
    }

    @Test
    public void propertiesViewIssuesFreshUnpaddedTokens() throws Exception {
        HttpTestSupport http = administrator();
        ShowPropertiesController controller = new ShowPropertiesController();
        assertNotNull(controller.execute(mapping(), null, http.request, http.response));
        String first = (String) http.attributes.get(AdminAccess.TOKEN_ATTRIBUTE);
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(first, http.requestAttributes.get("adminToken"));
        controller.execute(mapping(), null, http.request, http.response);
        String second = (String) http.attributes.get(AdminAccess.TOKEN_ATTRIBUTE);
        assertFalse(first.equals(second));
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(second).length);
    }

    @Test
    public void concurrentRequestsCanConsumeTokenOnlyOnce() throws Exception {
        HttpTestSupport http = administrator();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> consume = () -> {
            start.await();
            return AdminAccess.requireAdminMutation(http.request, http.response);
        };
        try {
            java.util.concurrent.Future<Boolean> first = pool.submit(consume);
            java.util.concurrent.Future<Boolean> second = pool.submit(consume);
            start.countDown();
            assertFalse(first.get(5, java.util.concurrent.TimeUnit.SECONDS).equals(
                    second.get(5, java.util.concurrent.TimeUnit.SECONDS)));
            assertNull(http.attributes.get(AdminAccess.TOKEN_ATTRIBUTE));
        } finally {
            pool.shutdownNow();
        }
    }

}
