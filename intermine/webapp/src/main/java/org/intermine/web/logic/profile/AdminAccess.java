package org.intermine.web.logic.profile;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.intermine.web.logic.session.SessionMethods;

/**
 * Authorization and request tokens for administrative forms.
 * @author InterMine Team
 */
public final class AdminAccess
{
    /** Session attribute holding the current administrative request token. */
    public static final String TOKEN_ATTRIBUTE = "ADMIN_CSRF_TOKEN";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AdminAccess() {
    }

    /**
     * Require an authenticated administrator before accessing administrative data.
     * @param request the current request
     * @param response the response to reject on failure
     * @return whether access is authorized
     * @throws IOException if the error response cannot be sent
     */
    public static boolean requireAdmin(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        if (!SessionMethods.isSuperUser(request.getSession())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }

    /**
     * Require an administrator, POST and a matching single-use request token.
     * @param request the current request
     * @param response the response to reject on failure
     * @return whether the mutation is authorized
     * @throws IOException if the error response cannot be sent
     */
    public static boolean requireAdminMutation(HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (!requireAdmin(request, response)) {
            return false;
        }
        if (!"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "POST");
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return false;
        }
        HttpSession session = request.getSession();
        String submitted = request.getParameter("adminToken");
        synchronized (session) {
            String expected = (String) session.getAttribute(TOKEN_ATTRIBUTE);
            if (expected != null && submitted != null && expected.length() == submitted.length()
                    && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                            submitted.getBytes(StandardCharsets.UTF_8))) {
                session.removeAttribute(TOKEN_ATTRIBUTE);
                return true;
            }
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
        return false;
    }

    /**
     * Issue a new token after authorizing the form's reader.
     * @param session the authenticated administrator's session
     * @return the token to render in the form
     */
    public static String issueToken(HttpSession session) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        synchronized (session) {
            session.setAttribute(TOKEN_ATTRIBUTE, token);
        }
        return token;
    }
}
