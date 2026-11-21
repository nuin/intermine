package org.intermine.web.registry;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.intermine.web.registry.model.Instance;
import org.intermine.web.registry.model.RegistryResponse;

/**
 * A bounded HTTP client for the registry's namespace and instance endpoints.
 * @author InterMine contributors
 */
public final class RegistryClient
{
    private final URI root;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * @param root registry base URI, including a trailing slash
     * @param timeout maximum connection and request duration
     */
    public RegistryClient(URI root, Duration timeout) {
        this.root = root;
        this.timeout = timeout;
    }

    /**
     * Look up the registered metadata for a mine.
     * @param mineUrl the mine's public base URL
     * @return instance metadata, or null when the registry has no successful result
     * @throws IOException if the registry cannot be reached or returns invalid JSON
     * @throws InterruptedException if this request is interrupted
     */
    public Instance findInstance(String mineUrl) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            byte[] namespaceBody = get(client, "service/namespace?url=" + encode(mineUrl));
            if (namespaceBody == null) {
                return null;
            }
            JsonNode namespaceResult = mapper.readTree(namespaceBody);
            JsonNode namespace = namespaceResult == null ? null : namespaceResult.get("namespace");
            if (namespace == null || !namespace.isTextual() || namespace.asText().isBlank()) {
                return null;
            }
            String segment = encode(namespace.asText()).replace("+", "%20").replace(".", "%2E");
            byte[] instanceBody = get(client, "service/instances/" + segment);
            RegistryResponse result = instanceBody == null ? null
                    : mapper.readValue(instanceBody, RegistryResponse.class);
            return result == null ? null : result.getInstance();
        }
    }

    private byte[] get(HttpClient client, String endpoint)
        throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(root.resolve(endpoint)).timeout(timeout)
                .header("Accept", "application/json").GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request,
                HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return response.statusCode() == 200 ? response.body() : null;
        } catch (TimeoutException exception) {
            throw new HttpTimeoutException("Registry response timed out");
        } catch (ExecutionException exception) {
            throw new IOException("Registry request failed", exception.getCause());
        } finally {
            pending.cancel(true);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
