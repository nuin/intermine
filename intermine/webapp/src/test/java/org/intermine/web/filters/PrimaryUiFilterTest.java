package org.intermine.web.filters;

import java.util.Map;
import java.util.Properties;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.intermine.web.logic.Constants;
import org.junit.Test;

import static org.easymock.EasyMock.*;

public class PrimaryUiFilterTest
{
    private final Properties properties = new Properties();
    private final PrimaryUiFilter filter = new PrimaryUiFilter(() -> properties,
            id -> id == 12 ? "Employee" : null);

    private void request(String method, String path, Map<String, String[]> params,
            boolean draft, int status, String location) throws Exception {
        HttpServletRequest request = createNiceMock(HttpServletRequest.class);
        HttpServletResponse response = createStrictMock(HttpServletResponse.class);
        FilterChain chain = createStrictMock(FilterChain.class);
        expect(request.getMethod()).andStubReturn(method);
        expect(request.getContextPath()).andStubReturn("/mine");
        expect(request.getRequestURI()).andStubReturn("/mine" + path);
        expect(request.getRequestURL()).andStubReturn(
                new StringBuffer("http://localhost:8080/mine" + path));
        expect(request.getParameterMap()).andStubReturn(params);
        if (draft) {
            HttpSession session = createNiceMock(HttpSession.class);
            expect(session.getAttribute(Constants.QUERY)).andStubReturn(new Object());
            replay(session);
            expect(request.getSession(false)).andStubReturn(session);
        }
        if (status == 0) {
            chain.doFilter(request, response);
        } else {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            if (status == 302) {
                response.setStatus(302);
                response.setHeader("Location", location);
            } else {
                response.sendError(eq(status), anyString());
            }
        }
        replay(request, response, chain);
        filter.doFilter(request, response, chain);
        verify(request, response, chain);
    }

    @Test
    public void configuredHomeAndHeadRedirectWithoutCreatingSession() throws Exception {
        properties.setProperty("bluegenes.primary.url", "https://ui.example/bluegenes/test");
        request("GET", "/", Map.of(), false, 302, "https://ui.example/bluegenes/test");
        request("HEAD", "/begin.do", Map.of(), false, 302, "https://ui.example/bluegenes/test");
    }

    @Test
    public void missingAndInvalidReportLinksReturnExplicitErrors() throws Exception {
        properties.setProperty("bluegenes.primary.url", "https://ui.example/bluegenes/test");
        request("GET", "/report.do", Map.of("id", new String[] {"13"}), false, 404, null);
        request("GET", "/report.do", Map.of("id", new String[] {"nonsense"}), false, 400, null);
    }

    @Test
    public void directlySelfReferentialTargetFailsInsteadOfLooping() throws Exception {
        properties.setProperty("bluegenes.primary.url", "http://localhost:8080/mine");
        request("GET", "/", Map.of(), false, 500, null);
    }

    @Test
    public void apiStaticPostDisabledAndDraftRequestsPassThrough() throws Exception {
        request("GET", "/", Map.of(), false, 0, null);
        properties.setProperty("bluegenes.primary.url", "https://ui.example/bluegenes/test");
        request("GET", "/service/model", Map.of(), false, 0, null);
        request("GET", "/model/images/logo.png", Map.of(), false, 0, null);
        request("POST", "/loadQuery.do", Map.of(), false, 0, null);
        request("GET", "/query.do", Map.of(), true, 0, null);
    }
}
