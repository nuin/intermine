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

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.intermine.api.InterMineAPI;
import org.intermine.api.profile.Profile;
import org.intermine.web.logic.oauth2.OAuthClientSupport;
import org.intermine.web.logic.oauth2.OAuthExchangeException;
import org.intermine.web.logic.oauth2.OAuthIdentityProfiles;
import org.intermine.web.logic.profile.LoginHandler;
import org.intermine.web.logic.profile.ProfileMergeIssues;
import org.intermine.web.struts.oauth2.DelegatedIdentity;
import org.intermine.webservice.server.core.JSONService;
import org.intermine.webservice.server.exceptions.ServiceException;
import org.intermine.webservice.server.user.JSONUserFormatter;
import org.json.JSONObject;

/**
 * Exchanges a client-managed authorization code for a temporary InterMine API token.
 * @author InterMine Team
 */
public class CallbackService extends JSONService
{
    /** @param im the mine */
    public CallbackService(InterMineAPI im) {
        super(im);
    }

    @Override
    protected String getResultsKey() {
        return "output";
    }

    @Override
    protected void execute() {
        String provider = getRequiredParameter("provider");
        String redirect = getRequiredParameter("redirect_uri");
        String code = getRequiredParameter("code");
        try {
            if (request.getParameter("error") != null) {
                throw new OAuthExchangeException("Invalid OAuth callback request", 400, null);
            }
            DelegatedIdentity identity = new OAuthClientSupport(webProperties)
                    .exchange(provider, code, URI.create(redirect));
            Profile profile = OAuthIdentityProfiles.getProfile(im, identity);
            Map<String, Object> result = new HashMap<>();
            result.put("user", new JSONObject(new JSONUserFormatter(profile).format()));
            result.put("token", im.getProfileManager().generate24hrKey(profile));
            Profile current = getPermission().getProfile();
            Map<String, String> renamed = Collections.emptyMap();
            if (current != null && !current.isLoggedIn()) {
                ProfileMergeIssues issues = LoginHandler.mergeProfiles(current, profile);
                renamed = issues.getRenamedBags();
            }
            result.put("renamedLists", new JSONObject(renamed));
            addResultItem(result, false);
        } catch (OAuthExchangeException e) {
            throw new ServiceException(e.getMessage(), e.getHttpStatus());
        } catch (IllegalArgumentException e) {
            throw new ServiceException("Invalid OAuth callback request", 400);
        } catch (RuntimeException e) {
            throw new ServiceException("OAuth callback could not complete", 502);
        }
    }
}
