package org.intermine.web.struts.oauth2;

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
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.struts.action.ActionErrors;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;
import org.intermine.web.context.InterMineContext;
import org.intermine.web.logic.oauth2.OAuthClientSupport;
import org.intermine.web.logic.oauth2.OAuthExchangeException;
import org.intermine.web.logic.profile.BrowserOAuthState;
import org.intermine.web.struts.InterMineAction;

/**
 * Starts a browser authorization-code login.
 * @author InterMine Team
 */
public class Authenticator extends InterMineAction
{
    /** {@inheritDoc} */
    @Override
    public ActionForward execute(ActionMapping mapping, ActionForm form,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        Properties properties = InterMineContext.getWebProperties();
        String provider = request.getParameter("provider");
        try {
            URI redirect = OAuthClientSupport.browserRedirectUri(properties, provider);
            String state = new BrowserOAuthState().begin(request.getSession(), provider, redirect);
            URI authorization = new OAuthClientSupport(properties)
                    .authorizationUri(provider, redirect, state);
            response.sendRedirect(authorization.toString());
            return null;
        } catch (OAuthExchangeException e) {
            ActionErrors errors = new ActionErrors();
            errors.add(ActionErrors.GLOBAL_MESSAGE,
                    new ActionMessage("oauth2.error.system-exception", e.getMessage()));
            saveErrors(request, errors);
            return mapping.findForward("login");
        }
    }
}
