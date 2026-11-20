package org.intermine.web.logic.profile;

import static org.easymock.EasyMock.*;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.intermine.api.profile.Profile;

final class HttpTestSupport {
    final Map<String, Object> attributes = new HashMap<>();
    final Map<String, Object> contextAttributes = new HashMap<>();
    final Map<String, Object> requestAttributes = new HashMap<>();
    final Map<String, String> parameters = new HashMap<>();
    final Map<String, String> headers = new HashMap<>();
    final HttpSession session = createMock(HttpSession.class);
    final ServletContext context = createMock(ServletContext.class);
    final HttpServletRequest request = createMock(HttpServletRequest.class);
    final HttpServletResponse response = createMock(HttpServletResponse.class);
    String method = "POST";
    int status;
    int rotations;

    HttpTestSupport() throws Exception {
        expect(session.getAttribute(anyString())).andAnswer(() ->
                attributes.get(getCurrentArgument(0))).anyTimes();
        session.setAttribute(anyString(), anyObject());
        expectLastCall().andAnswer(() -> {
            attributes.put(getCurrentArgument(0), getCurrentArgument(1));
            return null;
        }).anyTimes();
        session.removeAttribute(anyString());
        expectLastCall().andAnswer(() -> {
            attributes.remove(getCurrentArgument(0));
            return null;
        }).anyTimes();
        expect(session.getId()).andStubReturn("test-session");
        expect(session.getServletContext()).andStubReturn(context);
        expect(context.getAttribute(anyString())).andAnswer(() ->
                contextAttributes.get(getCurrentArgument(0))).anyTimes();
        expect(request.getSession()).andStubReturn(session);
        expect(request.getSession(false)).andStubReturn(session);
        expect(request.getMethod()).andAnswer(() -> method).anyTimes();
        expect(request.getParameter(anyString())).andAnswer(() ->
                parameters.get(getCurrentArgument(0))).anyTimes();
        expect(request.getAttribute(anyString())).andAnswer(() ->
                requestAttributes.get(getCurrentArgument(0))).anyTimes();
        request.setAttribute(anyString(), anyObject());
        expectLastCall().andAnswer(() -> {
            requestAttributes.put(getCurrentArgument(0), getCurrentArgument(1));
            return null;
        }).anyTimes();
        expect(request.changeSessionId()).andAnswer(() -> {
            rotations++;
            return "rotated-session";
        }).anyTimes();
        response.sendError(anyInt());
        expectLastCall().andAnswer(() -> {
            status = getCurrentArgument(0);
            return null;
        }).anyTimes();
        response.setHeader(anyString(), anyString());
        expectLastCall().andAnswer(() -> {
            headers.put(getCurrentArgument(0), getCurrentArgument(1));
            return null;
        }).anyTimes();
        replay(session, context, request, response);
    }

    static Profile profile(boolean administrator) {
        Profile profile = createMock(Profile.class);
        expect(profile.isSuperuser()).andStubReturn(administrator);
        expect(profile.isLoggedIn()).andStubReturn(true);
        expect(profile.getUsername()).andStubReturn(administrator ? "admin" : "ordinary");
        expect(profile.getUserId()).andStubReturn(administrator ? 1 : 2);
        replay(profile);
        return profile;
    }
}
