package org.intermine.web.logic.oauth2;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.nimbusds.common.contenttype.ContentType;
import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils;
import com.nimbusds.oauth2.sdk.util.URLUtils;
import net.minidev.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.intermine.web.struts.oauth2.BuiltinOAuthProvider;
import org.intermine.web.struts.oauth2.CustomOAuthProvider;
import org.intermine.web.struts.oauth2.DefaultOAuthProvider;
import org.intermine.web.struts.oauth2.DelegatedIdentity;
import org.intermine.web.struts.oauth2.MessageFormat;
import org.intermine.web.struts.oauth2.OAuthProvider;

/**
 * Shared authorization-code exchange for browser and REST entry points.
 * @author InterMine Team
 */
public final class OAuthClientSupport
{
    private final Properties properties;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    /** @param properties provider and mine configuration */
    public OAuthClientSupport(Properties properties) {
        this(properties, 10000, 10000);
    }

    /**
     * @param properties provider and mine configuration
     * @param connectTimeoutMillis positive connection timeout
     * @param readTimeoutMillis positive read timeout
     */
    public OAuthClientSupport(Properties properties, int connectTimeoutMillis,
            int readTimeoutMillis) {
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0) {
            throw new IllegalArgumentException("OAuth timeouts must be positive");
        }
        this.properties = properties;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * @param providerName configured provider
     * @param redirectUri callback URI, or null for a REST client-managed link
     * @param state correlation nonce, or null for a REST client-managed link
     * @return the authorization URI
     */
    public URI authorizationUri(String providerName, URI redirectUri, String state) {
        try {
            String prefix = prefix(providerName);
            String endpoint = properties.getProperty(prefix + ".url.auth");
            if (endpoint == null) {
                endpoint = BuiltinOAuthProvider.valueOf(providerName).getAuthzEndpoint();
            }
            AuthorizationRequest.Builder builder = new AuthorizationRequest.Builder(
                    ResponseType.CODE, new ClientID(required(properties, prefix + ".client-id")))
                    .endpointURI(endpoint(endpoint));
            String scopes = properties.getProperty(prefix + ".scopes");
            if (!StringUtils.isBlank(scopes)) {
                builder.scope(Scope.parse(scopes));
            }
            if (redirectUri != null) {
                builder.redirectionURI(endpoint(redirectUri.toString()));
            }
            if (state != null) {
                builder.state(new State(state));
            }
            String realm = properties.getProperty("webapp.baseurl");
            if (realm != null) {
                builder.customParameter("openid.realm", realm);
            }
            return builder.build().toHTTPRequest().getURI();
        } catch (RuntimeException e) {
            throw configurationFailure(e);
        }
    }

    /**
     * @param properties mine configuration
     * @param providerName configured provider
     * @return the encoded browser callback URI
     */
    public static URI browserRedirectUri(Properties properties, String providerName) {
        try {
            prefix(providerName);
            String base = required(properties, "webapp.baseurl").replaceAll("/+$", "");
            String path = properties.getProperty("webapp.path", "").replaceAll("^/+|/+$", "");
            return endpoint(base + (path.isEmpty() ? "" : "/" + path)
                    + "/oauth2callback.do?provider="
                    + URLEncoder.encode(providerName, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw configurationFailure(e);
        }
    }

    /**
     * @param providerName configured provider
     * @param code the authorization code
     * @param redirectUri the same redirect URI sent in the authorization request
     * @return the identity returned by the configured userinfo endpoint
     */
    public DelegatedIdentity exchange(String providerName, String code, URI redirectUri) {
        if (StringUtils.isBlank(code) || redirectUri == null) {
            throw new OAuthExchangeException("Invalid OAuth callback request", 400, null);
        }
        OAuthProvider provider;
        HTTPRequest tokenRequest;
        URI resource;
        String mechanism;
        try {
            String prefix = prefix(providerName);
            provider = properties.containsKey(prefix + ".url.token")
                    ? new CustomOAuthProvider(properties, providerName)
                    : new DefaultOAuthProvider(properties, providerName);
            resource = endpoint(required(properties, prefix + ".identity-resource"));
            mechanism = properties.getProperty(prefix + ".resource-auth-mechanism", "queryparam");
            if (!List.of("header", "queryparam", "body").contains(mechanism)) {
                throw new IllegalArgumentException("Invalid resource authorization mode");
            }
            tokenRequest = new TokenRequest.Builder(endpoint(provider.getTokenUrl()),
                    new ClientSecretPost(new ClientID(provider.getClientId()),
                        new Secret(provider.getClientSecret())),
                    new AuthorizationCodeGrant(new AuthorizationCode(code),
                        endpoint(redirectUri.toString()))).build().toHTTPRequest();
            if (provider.getMessageFormat() == MessageFormat.QUERY) {
                tokenRequest.appendQueryString(tokenRequest.getBody());
                tokenRequest.setBody(null);
            }
            if (provider.getResponseType() == org.intermine.web.struts.oauth2.ResponseType.JSON) {
                tokenRequest.setAccept("application/json");
            }
        } catch (RuntimeException e) {
            throw configurationFailure(e);
        }
        String token = readToken(send(tokenRequest, "token"), provider);
        HTTPRequest identityRequest = resourceRequest(resource, mechanism, token);
        return readIdentity(providerName, send(identityRequest, "identity").getBody());
    }

    private HTTPRequest resourceRequest(URI endpoint, String mechanism, String token) {
        HTTPRequest request = new HTTPRequest("body".equals(mechanism)
                ? HTTPRequest.Method.POST : HTTPRequest.Method.GET, endpoint);
        request.setAccept("application/json");
        Map<String, List<String>> bearer = Map.of("access_token", List.of(token));
        if ("header".equals(mechanism)) {
            request.setAuthorization("Bearer " + token);
        } else if ("queryparam".equals(mechanism)) {
            request.appendQueryParameters(bearer);
        } else {
            request.setEntityContentType(ContentType.APPLICATION_URLENCODED);
            request.setBody(URLUtils.serializeParameters(bearer));
        }
        return request;
    }

    private HTTPResponse send(HTTPRequest request, String phase) {
        request.setFollowRedirects(false);
        request.setConnectTimeout(connectTimeoutMillis);
        request.setReadTimeout(readTimeoutMillis);
        try {
            HTTPResponse response = request.send();
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new OAuthExchangeException("OAuth " + phase + " endpoint returned HTTP "
                        + response.getStatusCode(), 502, null);
            }
            return response;
        } catch (IOException e) {
            throw new OAuthExchangeException("OAuth " + phase + " exchange failed", 502, e);
        }
    }

    private String readToken(HTTPResponse response, OAuthProvider provider) {
        try {
            JSONObject json;
            if (provider.getResponseType() == org.intermine.web.struts.oauth2.ResponseType.FORM) {
                json = new JSONObject();
                for (Map.Entry<String, List<String>> entry
                        : URLUtils.parseParameters(response.getBody()).entrySet()) {
                    if (entry.getValue().size() != 1) {
                        throw new ParseException("Duplicate token response field");
                    }
                    json.put(entry.getKey(), entry.getValue().get(0));
                }
            } else {
                json = JSONObjectUtils.parse(response.getBody());
            }
            if (json.containsKey("error")) {
                throw new ParseException("Provider rejected the token request");
            }
            if (!json.containsKey("token_type")) {
                json.put("token_type", "Bearer");
            }
            if (!(json.get("access_token") instanceof String)
                    || StringUtils.isBlank((String) json.get("access_token"))
                    || !"Bearer".equalsIgnoreCase(String.valueOf(json.get("token_type")))) {
                throw new ParseException("Invalid bearer token response");
            }
            return AccessTokenResponse.parse(json).getTokens().getAccessToken().getValue();
        } catch (ParseException | RuntimeException e) {
            throw new OAuthExchangeException("Invalid OAuth token response", 502, e);
        }
    }

    private DelegatedIdentity readIdentity(String provider, String body) {
        try {
            String prefix = prefix(provider);
            org.json.JSONObject json = new org.json.JSONObject(body);
            if (json.has("error")) {
                throw new IllegalArgumentException("Provider rejected userinfo");
            }
            String envelope = properties.getProperty(prefix + ".identity-envelope");
            if (!StringUtils.isBlank(envelope)) {
                json = json.getJSONObject(envelope);
            }
            if (json.has("error")) {
                throw new IllegalArgumentException("Provider rejected userinfo");
            }
            Object id = json.get(properties.getProperty(prefix + ".id-key", "id"));
            if (!(id instanceof String || id instanceof Number)
                    || StringUtils.isBlank(id.toString())) {
                throw new IllegalArgumentException("Missing identity ID");
            }
            String[] nameKeys = properties.getProperty(prefix + ".name-key", "name").split(",");
            String[] names = new String[nameKeys.length];
            for (int i = 0; i < nameKeys.length; i++) {
                names[i] = json.optString(nameKeys[i].trim(), "");
            }
            org.json.JSONObject emails = json.optJSONObject("emails");
            String email = emails == null
                    ? json.optString(properties.getProperty(prefix + ".email-key", "email"), "")
                    : emails.optString("preferred", "");
            if (emails != null && StringUtils.isEmpty(email)) {
                email = emails.optString("account", "");
            }
            return new DelegatedIdentity(provider, id.toString(), email, String.join(" ", names));
        } catch (RuntimeException e) {
            throw new OAuthExchangeException("Invalid OAuth identity response", 502, e);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("Missing OAuth configuration");
        }
        return value;
    }

    private static String prefix(String provider) {
        if (StringUtils.isBlank(provider)) {
            throw new IllegalArgumentException("Missing provider");
        }
        return "oauth2." + provider;
    }

    private static URI endpoint(String value) {
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getFragment() != null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid OAuth endpoint");
        }
        return uri;
    }

    private static OAuthExchangeException configurationFailure(RuntimeException cause) {
        return new OAuthExchangeException("Invalid OAuth provider configuration", 400, cause);
    }
}
