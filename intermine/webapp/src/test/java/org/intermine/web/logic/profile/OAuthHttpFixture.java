package org.intermine.web.logic.profile;

import static org.easymock.EasyMock.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class OAuthHttpFixture implements AutoCloseable {
    static final String SECRET = "secret-sentinel +&\"雪";
    static final String CODE = "code-sentinel +&\"雪";
    static final String TOKEN = "access-token-sentinel";
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    final List<WireRequest> requests = Collections.synchronizedList(new ArrayList<>());
    final List<IOException> disconnects = Collections.synchronizedList(new ArrayList<>());
    int tokenStatus = 200;
    int identityStatus = 200;
    String tokenBody = "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\"}";
    String identityBody = "{\"id\":\"provider-123\",\"name\":\"Provider Name\","
            + "\"email\":\"provider@example.test\"}";
    String tokenContentType = "application/json";
    String identityRedirect;
    CountDownLatch blockToken;
    final CountDownLatch tokenArrived = new CountDownLatch(1);

    OAuthHttpFixture() throws IOException {
        server.createContext("/", this::respond);
        server.setExecutor(executor);
        server.start();
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    Properties properties(String provider) {
        Properties props = new Properties();
        String prefix = "oauth2." + provider;
        props.setProperty("webapp.baseurl", "http://mine.example.test");
        props.setProperty("webapp.path", "mine");
        props.setProperty(prefix + ".url.auth", uri("/authorize?tenant=existing").toString());
        props.setProperty(prefix + ".url.token", uri("/token?tenant=existing").toString());
        props.setProperty(prefix + ".identity-resource", uri("/identity?tenant=existing").toString());
        props.setProperty(prefix + ".client-id", "client +&雪");
        props.setProperty(prefix + ".client-secret", SECRET);
        props.setProperty(prefix + ".messageformat", "BODY");
        props.setProperty(prefix + ".responsetype", "JSON");
        props.setProperty(prefix + ".resource-auth-mechanism", "header");
        props.setProperty(prefix + ".scopes", "profile email");
        return props;
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(new WireRequest(exchange.getRequestMethod(), exchange.getRequestURI(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        boolean token = exchange.getRequestURI().getPath().equals("/token");
        boolean identity = exchange.getRequestURI().getPath().equals("/identity");
        if (token) {
            tokenArrived.countDown();
            if (blockToken != null) {
                try {
                    if (!blockToken.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("Test token response was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        }
        byte[] body = (token ? tokenBody : identityBody).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", token ? tokenContentType : "application/json");
        if (identity && identityRedirect != null) {
            exchange.getResponseHeaders().set("Location", identityRedirect);
        }
        try {
            exchange.sendResponseHeaders(token ? tokenStatus : identity ? identityStatus : 200, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException e) {
            disconnects.add(e);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    static final class WireRequest {
        final String method;
        final URI uri;
        final String authorization;
        final String body;

        WireRequest(String method, URI uri, String authorization, String body) {
            this.method = method;
            this.uri = uri;
            this.authorization = authorization;
            this.body = body;
        }
    }

    private static final class SessionState {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final Map<String, Object> contextValues;
        final HttpSession session = createNiceMock(HttpSession.class);
        final ServletContext context = createNiceMock(ServletContext.class);
        volatile boolean invalidated;

        SessionState(Map<String, Object> contextValues) {
            this.contextValues = contextValues;
            expect(session.getServletContext()).andStubReturn(context);
            expect(session.getAttribute(anyString())).andAnswer(() -> {
                checkValid();
                return values.get(getCurrentArgument(0));
            }).anyTimes();
            session.setAttribute(anyString(), anyObject());
            expectLastCall().andAnswer(() -> {
                checkValid();
                String key = getCurrentArgument(0);
                Object value = getCurrentArgument(1);
                if (value == null) { values.remove(key); } else { values.put(key, value); }
                return null;
            }).anyTimes();
            session.removeAttribute(anyString());
            expectLastCall().andAnswer(() -> { checkValid(); values.remove(getCurrentArgument(0)); return null; }).anyTimes();
            session.invalidate();
            expectLastCall().andAnswer(() -> { checkValid(); invalidated = true; values.clear(); return null; }).anyTimes();
            expect(context.getAttribute(anyString())).andAnswer(() -> contextValues.get(getCurrentArgument(0))).anyTimes();
            replay(session, context);
        }

        private void checkValid() {
            if (invalidated) { throw new IllegalStateException("Session invalidated"); }
        }
    }

    static final class Browser {
        final Map<String, Object> sessionValues;
        final Map<String, Object> contextValues;
        final Map<String, Object> requestValues = new HashMap<>();
        final Map<String, String> parameters = new HashMap<>();
        final Map<String, String[]> repeatedParameters = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> responseHeaders = new HashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final HttpSession session;
        final HttpServletRequest request = createNiceMock(HttpServletRequest.class);
        final HttpServletResponse response = createNiceMock(HttpServletResponse.class);
        private SessionState active;
        int status = 200;
        int rotations;
        String redirect;
        String method = "GET";
        String pathInfo;
        String queryString;

        Browser() throws IOException {
            this(new SessionState(new ConcurrentHashMap<>()));
        }

        Browser(Browser previous) throws IOException {
            this(previous.active);
        }

        private Browser(SessionState initial) throws IOException {
            active = initial;
            session = initial.session;
            sessionValues = initial.values;
            contextValues = initial.contextValues;
            expect(request.getSession()).andAnswer(this::currentSession).anyTimes();
            expect(request.getSession(false)).andAnswer(() -> active.invalidated ? null : active.session).anyTimes();
            expect(request.getMethod()).andAnswer(() -> method).anyTimes();
            expect(request.getPathInfo()).andAnswer(() -> pathInfo).anyTimes();
            expect(request.getQueryString()).andAnswer(() -> queryString).anyTimes();
            expect(request.getHeader(anyString())).andAnswer(() -> headers.get(getCurrentArgument(0))).anyTimes();
            expect(request.getParameter(anyString())).andAnswer(() -> parameters.get(getCurrentArgument(0))).anyTimes();
            expect(request.getParameterMap()).andAnswer(() -> {
                Map<String, String[]> result = new HashMap<>();
                parameters.forEach((k, v) -> result.put(k, new String[] {v}));
                result.putAll(repeatedParameters);
                return result;
            }).anyTimes();
            expect(request.getAttribute(anyString())).andAnswer(() -> requestValues.get(getCurrentArgument(0))).anyTimes();
            request.setAttribute(anyString(), anyObject());
            expectLastCall().andAnswer(() -> { requestValues.put(getCurrentArgument(0), getCurrentArgument(1)); return null; }).anyTimes();
            request.removeAttribute(anyString());
            expectLastCall().andAnswer(() -> { requestValues.remove(getCurrentArgument(0)); return null; }).anyTimes();
            expect(request.changeSessionId()).andAnswer(() -> "session-" + ++rotations).anyTimes();
            expect(response.getOutputStream()).andStubReturn(new ServletOutputStream() {
                @Override public void write(int b) { body.write(b); }
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener(WriteListener listener) { throw new UnsupportedOperationException(); }
            });
            response.setStatus(anyInt());
            expectLastCall().andAnswer(() -> { status = getCurrentArgument(0); return null; }).anyTimes();
            response.setHeader(anyString(), anyString());
            expectLastCall().andAnswer(() -> { responseHeaders.put(getCurrentArgument(0), getCurrentArgument(1)); return null; }).anyTimes();
            response.sendRedirect(anyString());
            expectLastCall().andAnswer(() -> { redirect = getCurrentArgument(0); return null; }).anyTimes();
            replay(request, response);
            parameters.put("format", "json");
            parameters.put("provider", "TEST");
            parameters.put("redirect_uri", "http://client.example.test/callback?x=1&y=雪");
            parameters.put("code", CODE);
        }

        private HttpSession currentSession() {
            if (active.invalidated) {
                active = new SessionState(active.contextValues);
            }
            return active.session;
        }

        String text() {
            return body.toString(StandardCharsets.UTF_8);
        }
    }
}
