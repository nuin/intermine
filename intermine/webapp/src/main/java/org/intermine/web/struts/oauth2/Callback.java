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

import java.net.URI;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.apache.struts.action.ActionErrors;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;
import org.apache.struts.action.ActionMessages;
import org.intermine.api.InterMineAPI;
import org.intermine.api.profile.Profile;
import org.intermine.web.context.InterMineContext;
import org.intermine.web.logic.oauth2.OAuthClientSupport;
import org.intermine.web.logic.oauth2.OAuthExchangeException;
import org.intermine.web.logic.oauth2.OAuthIdentityProfiles;
import org.intermine.web.logic.profile.LoginHandler;
import org.intermine.web.logic.profile.ProfileMergeIssues;
import org.intermine.web.logic.session.SessionMethods;

/**
 * Completes browser login using the configured provider's userinfo endpoint.
 * @author InterMine Team
 */
public class Callback extends LoginHandler
{
    private static final Logger LOG = Logger.getLogger(Callback.class);

    /** {@inheritDoc} */
    @Override
    public ActionForward execute(ActionMapping mapping, ActionForm form,
            HttpServletRequest request, HttpServletResponse response) {
        Properties properties = InterMineContext.getWebProperties();
        String provider = request.getParameter("provider");
        try {
            String state = (String) request.getSession().getAttribute("oauth2.state");
            if (state == null || !state.equals(request.getParameter("state"))
                    || request.getParameter("error") != null) {
                throw new OAuthExchangeException("Invalid OAuth callback request", 400, null);
            }
            URI redirect = OAuthClientSupport.browserRedirectUri(properties, provider);
            DelegatedIdentity identity = new OAuthClientSupport(properties)
                    .exchange(provider, request.getParameter("code"), redirect);
            saveMessages(request, loginUser(request, identity));
            return mapping.findForward("mymine");
        } catch (OAuthExchangeException e) {
            LOG.warn(e.getMessage());
            return failure(mapping, request, e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("OAuth browser login could not complete");
            return failure(mapping, request, "OAuth login could not complete");
        }
    }

    private ActionForward failure(ActionMapping mapping, HttpServletRequest request,
            String message) {
        ActionErrors errors = new ActionErrors();
        errors.add(ActionErrors.GLOBAL_MESSAGE,
                new ActionMessage("oauth2.error.granting", message));
        saveErrors(request, errors);
        return mapping.findForward("login");
    }

    private ActionMessages loginUser(HttpServletRequest request, DelegatedIdentity identity) {
        InterMineAPI api = SessionMethods.getInterMineAPI(request.getSession());
        Profile profile = OAuthIdentityProfiles.getProfile(api, identity);
        ProfileMergeIssues issues = completeLogin(request, profile);
        api.getTrackerDelegate().trackLogin(profile.getUsername());
        ActionMessages messages = new ActionMessages();
        messages.add(ActionMessages.GLOBAL_MESSAGE, new ActionMessage(
                "login.oauth2.successful", identity.getProvider(), profile.getName()));
        for (Map.Entry<String, String> pair : issues.getRenamedBags().entrySet()) {
            messages.add(ActionMessages.GLOBAL_MESSAGE,
                    new ActionMessage("login.renamed.bag", pair.getKey(), pair.getValue()));
        }
        for (Map.Entry<String, String> pair : issues.getRenamedTemplates().entrySet()) {
            messages.add(ActionMessages.GLOBAL_MESSAGE,
                    new ActionMessage("login.failedtemplate", pair.getKey(), pair.getValue()));
        }
        return messages;
    }
}
