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

import org.intermine.api.InterMineAPI;
import org.intermine.web.logic.oauth2.OAuthClientSupport;
import org.intermine.web.logic.oauth2.OAuthExchangeException;
import org.intermine.webservice.server.core.JSONService;
import org.intermine.webservice.server.exceptions.ServiceException;

/**
 * Builds an authorization link for a client-managed OAuth flow.
 * @author InterMine Team
 */
public class AuthenticatorService extends JSONService
{
    /** @param im the mine */
    public AuthenticatorService(InterMineAPI im) {
        super(im);
    }

    @Override
    protected void execute() {
        String provider = getRequiredParameter("provider");
        try {
            String link = new OAuthClientSupport(webProperties)
                    .authorizationUri(provider, null, null).toString();
            addResultEntry("link", link, false);
        } catch (OAuthExchangeException e) {
            throw new ServiceException(e.getMessage(), e.getHttpStatus());
        } catch (RuntimeException e) {
            throw new ServiceException("OAuth authorization could not complete", 502);
        }
    }
}
