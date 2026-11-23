package org.intermine.webservice.server.oauth2;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.intermine.api.InterMineAPI;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.ProfileManager;
import org.intermine.web.logic.oauth2.OAuthExchangeException;
import org.intermine.web.logic.oauth2.OAuthTransactions;
import org.json.JSONObject;

/**
 * Strict JSON/form POST boundary for server-to-server OAuth transactions.
 * @author InterMine Team
 */
public final class TransactionService
{
    private static final Map<String, Set<String>> PARAMETERS = Map.of(
            "/prepare", Set.of("provider", "redirect_uri", "state", "token"),
            "/exchange", Set.of("handle", "code"),
            "/complete", Set.of("handle"),
            "/acknowledge", Set.of("handle"),
            "/cancel", Set.of("handle"),
            "/revoke", Set.of("token"));
    private final InterMineAPI api;
    private final OAuthTransactions transactions;

    /** @param api the mine
     * @param transactions application-owned transaction lifecycle */
    public TransactionService(InterMineAPI api, OAuthTransactions transactions) {
        this.api = api;
        this.transactions = transactions;
    }

    /** Handle a transaction operation without logging credentials or upstream bodies.
     * @param request HTTP request
     * @param response HTTP response
     * @throws IOException if the client disconnects */
    public void service(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json; charset=UTF-8");
        JSONObject body;
        int status = 200;
        try {
            body = new JSONObject(Map.of("wasSuccessful", true, "statusCode", status,
                    "output", execute(request)));
        } catch (OAuthExchangeException error) {
            status = error.getHttpStatus();
            body = failure(status, error.getMessage());
        } catch (IllegalArgumentException error) {
            status = 400;
            body = failure(status, "Invalid OAuth transaction request");
        } catch (RuntimeException error) {
            status = 502;
            body = failure(status, "OAuth transaction could not complete");
        }
        response.setStatus(status);
        response.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> execute(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            throw new OAuthExchangeException("OAuth transactions require POST", 405, null);
        }
        if (request.getQueryString() != null) {
            throw invalid();
        }
        Map<String, String[]> parameters = request.getParameterMap();
        Set<String> allowed = PARAMETERS.get(String.valueOf(request.getPathInfo()));
        if (allowed == null) {
            throw new OAuthExchangeException("Unknown OAuth transaction operation", 404, null);
        }
        for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw invalid();
            }
        }
        if ("/prepare".equals(request.getPathInfo())) {
            return transactions.prepare(source(request), required(parameters, "provider", 128),
                    URI.create(required(parameters, "redirect_uri", 2048)),
                    required(parameters, "state", 1024));
        }
        if ("/revoke".equals(request.getPathInfo())) {
            revoke(request);
            return Map.of("success", true);
        }
        String handle = required(parameters, "handle", 128);
        switch (request.getPathInfo()) {
            case "/exchange":
                transactions.exchange(handle, required(parameters, "code", 8192));
                break;
            case "/complete":
                return transactions.complete(handle);
            case "/acknowledge":
                transactions.acknowledge(handle);
                break;
            case "/cancel":
                transactions.cancel(handle);
                break;
            default:
                throw invalid();
        }
        return Map.of("success", true);
    }

    private Profile source(HttpServletRequest request) {
        String token = token(request);
        if (token == null) {
            return null;
        }
        try {
            ProfileManager.ApiPermission permission = api.getProfileManager().getPermission(token,
                    api.getClassKeys());
            if (!permission.isRW()) {
                throw new OAuthExchangeException("Anonymous work requires write access", 403, null);
            }
            Profile profile = permission.getProfile();
            return profile.isLoggedIn() ? null : profile;
        } catch (ProfileManager.AuthenticationException error) {
            throw new OAuthExchangeException("Invalid source credentials", 401, null);
        }
    }

    private void revoke(HttpServletRequest request) {
        String token = token(request);
        if (token == null) {
            throw invalid();
        }
        ProfileManager manager = api.getProfileManager();
        try {
            Profile owner = manager.getPermission(token, api.getClassKeys()).getProfile();
            if (!manager.revokeTemporaryToken(token, owner)) {
                manager.getPermission(token, api.getClassKeys());
                throw new OAuthExchangeException("Only temporary credentials can be revoked",
                        400, null);
            }
        } catch (ProfileManager.AuthenticationException error) {
            return;
        }
    }

    private static String token(HttpServletRequest request) {
        String token = request.getParameter("token");
        String header = request.getHeader("Authorization");
        if (header != null) {
            if (token != null || !header.startsWith("Token ")) {
                throw invalid();
            }
            token = header.substring("Token ".length());
        }
        if (token == null) {
            return null;
        }
        if (token.isBlank() || token.length() > 1024) {
            throw invalid();
        }
        return token;
    }

    private static String required(Map<String, String[]> parameters, String key, int maxLength) {
        String[] values = parameters.get(key);
        if (values == null || values.length != 1 || values[0] == null || values[0].isBlank()
                || values[0].length() > maxLength) {
            throw invalid();
        }
        return values[0];
    }

    private static JSONObject failure(int status, String message) {
        return new JSONObject(Map.of("wasSuccessful", false,
                "statusCode", status, "error", message));
    }

    private static OAuthExchangeException invalid() {
        return new OAuthExchangeException("Invalid OAuth transaction request", 400, null);
    }
}
