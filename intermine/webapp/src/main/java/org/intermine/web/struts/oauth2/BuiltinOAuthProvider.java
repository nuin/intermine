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

/** Historical provider endpoints; explicit web properties take precedence. */
public enum BuiltinOAuthProvider
{
    /** Facebook endpoints. */
    FACEBOOK("https://graph.facebook.com/oauth/authorize",
            "https://graph.facebook.com/oauth/access_token"),
    /** Foursquare endpoints. */
    FOURSQUARE("https://foursquare.com/oauth2/authenticate",
            "https://foursquare.com/oauth2/access_token"),
    /** Github endpoints. */
    GITHUB("https://github.com/login/oauth/authorize",
            "https://github.com/login/oauth/access_token"),
    /** Google endpoints. */
    GOOGLE("https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token"),
    /** Instagram endpoints. */
    INSTAGRAM("https://api.instagram.com/oauth/authorize",
            "https://api.instagram.com/oauth/access_token"),
    /** Linkedin endpoints. */
    LINKEDIN("https://www.linkedin.com/uas/oauth2/authorization",
            "https://www.linkedin.com/uas/oauth2/accessToken"),
    /** Microsoft endpoints. */
    MICROSOFT("https://login.live.com/oauth20_authorize.srf",
            "https://login.live.com/oauth20_token.srf"),
    /** Paypal endpoints. */
    PAYPAL("https://identity.x.com/xidentity/resources/authorize",
            "https://identity.x.com/xidentity/oauthtokenservice"),
    /** Reddit endpoints. */
    REDDIT("https://ssl.reddit.com/api/v1/authorize",
            "https://ssl.reddit.com/api/v1/access_token"),
    /** Salesforce endpoints. */
    SALESFORCE("https://login.salesforce.com/services/oauth2/authorize",
            "https://login.salesforce.com/services/oauth2/token"),
    /** Yammer endpoints. */
    YAMMER("https://www.yammer.com/dialog/oauth",
            "https://www.yammer.com/oauth2/access_token.json");

    private final String authzEndpoint;
    private final String tokenEndpoint;

    /**
     * @param authzEndpoint authorization endpoint
     * @param tokenEndpoint token endpoint
     */
    BuiltinOAuthProvider(String authzEndpoint, String tokenEndpoint) {
        this.authzEndpoint = authzEndpoint;
        this.tokenEndpoint = tokenEndpoint;
    }

    /** @return the authorization endpoint */
    public String getAuthzEndpoint() {
        return authzEndpoint;
    }

    /** @return the token endpoint */
    public String getTokenEndpoint() {
        return tokenEndpoint;
    }
}
