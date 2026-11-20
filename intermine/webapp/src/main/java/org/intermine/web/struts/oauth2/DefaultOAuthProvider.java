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

import java.util.Properties;

import org.apache.commons.lang.StringUtils;

/**
 * A representation of an OAuth provider which is one of the default
 * providers provided for end-users.
 *
 * @author Alex Kalderimis
 *
 */
public final class DefaultOAuthProvider implements OAuthProvider
{

    private final String clientId, clientSecret;
    private final BuiltinOAuthProvider provider;
    private final Properties properties;

    /**
     * Construct a new provider.
     *
     * @param properties The properties where the client-id and client-secret can be looked up.
     * @param providerName The built-in provider name.
     */
    public DefaultOAuthProvider(Properties properties, String providerName) {
        this.provider = BuiltinOAuthProvider.valueOf(providerName);
        this.properties = properties;
        String prefix = "oauth2." + provider.name();
        clientId = properties.getProperty(prefix + ".client-id");
        clientSecret = properties.getProperty(prefix + ".client-secret");
        if (StringUtils.isBlank(clientId) || StringUtils.isBlank(clientSecret)) {
            throw new IllegalArgumentException("Bad config: no client config for " + provider);
        }
    }

    @Override
    public String getTokenUrl() {
        return properties.getProperty("oauth2." + provider.name() + ".url.token",
                provider.getTokenEndpoint());
    }

    @Override
    public MessageFormat getMessageFormat() {
        String defaultFormat = provider == BuiltinOAuthProvider.MICROSOFT
                || provider == BuiltinOAuthProvider.GOOGLE
                || provider == BuiltinOAuthProvider.GITHUB ? "BODY" : "QUERY";
        return MessageFormat.valueOf(properties.getProperty(
                "oauth2." + provider.name() + ".messageformat", defaultFormat));
    }

    @Override
    public ResponseType getResponseType() {
        String defaultType = provider == BuiltinOAuthProvider.GITHUB
                || provider == BuiltinOAuthProvider.FACEBOOK ? "FORM" : "JSON";
        return ResponseType.valueOf(properties.getProperty(
                "oauth2." + provider.name() + ".responsetype", defaultType));
    }

    @Override
    public String getName() {
        return provider.name();
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public String getClientSecret() {
        return clientSecret;
    }

}
