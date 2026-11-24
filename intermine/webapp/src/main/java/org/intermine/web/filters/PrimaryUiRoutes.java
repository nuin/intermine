package org.intermine.web.filters;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.intermine.objectstore.ObjectStoreException;

/**
 * Maps supported legacy browser entry points to a configured BlueGenes mine.
 * @author InterMine developers
 */
public final class PrimaryUiRoutes
{
    private static final Map<String, String> PAGES = Map.of(
            "/", "", "/index.jsp", "", "/begin.do", "", "/templates.do", "/templates",
            "/bag.do", "/upload", "/query.do", "/querybuilder");
    private static final Map<String, Set<String>> PARAMETERS = Map.of(
            "/report.do", Set.of("id"),
            "/template.do", Set.of("name"),
            "/bagDetails.do", Set.of("bagName", "name"),
            "/loadQuery.do", Set.of("method", "query", "skipBuilder"));
    private final String root;

    /**
     * Resolves a report ID without creating or transferring a browser session.
     */
    @FunctionalInterface
    public interface ReportTypeResolver
    {
        /**
         * @param id Object identifier
         * @return The model type, or null if the object does not exist
         * @throws ObjectStoreException if the object store cannot be read
         */
        String resolve(int id) throws ObjectStoreException;
    }

    /**
     * @param target Complete BlueGenes mine URL; blank disables the bridge
     * @throws IllegalArgumentException if a nonblank target is unsafe or incomplete
     */
    public PrimaryUiRoutes(String target) {
        root = target == null || target.isBlank() ? null : validateTarget(target);
    }

    private static String validateTarget(String target) {
        URI uri = URI.create(target);
        String path = uri.getRawPath();
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]")
                .contains(uri.getHost() == null ? "" : uri.getHost());
        boolean secure = "https".equals(uri.getScheme())
                || ("http".equals(uri.getScheme()) && loopback);
        if (!secure || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || path == null || path.length() < 2 || path.contains("%")
                || path.contains(";") || path.contains("//") || !uri.normalize().equals(uri)
                || path.endsWith("/.") || path.endsWith("/..")) {
            throw new IllegalArgumentException("bluegenes.primary.url must be a public mine URL");
        }
        String ascii = uri.toASCIIString();
        return ascii.endsWith("/") ? ascii.substring(0, ascii.length() - 1) : ascii;
    }

    /**
     * @param path Path relative to the InterMine deployment
     * @return Whether the path is a supported browser entry point
     */
    public static boolean isEntryPath(String path) {
        return PAGES.containsKey(path) || PARAMETERS.containsKey(path);
    }

    /**
     * @param path Path relative to the InterMine deployment
     * @param parameters Decoded request parameters, retaining duplicate values
     * @param sessionQuery Whether an existing legacy query draft must be preserved
     * @param resolver Object store lookup used only for report links
     * @return Destination URL, or null when the legacy handler must be retained
     * @throws ObjectStoreException if the report lookup fails
     * @throws IllegalArgumentException if a supported link has invalid parameters
     * @throws NoSuchElementException if a report object does not exist
     */
    public String destination(String path, Map<String, String[]> parameters,
            boolean sessionQuery, ReportTypeResolver resolver) throws ObjectStoreException {
        if (root == null || !isEntryPath(path) || "legacy".equals(value(parameters, "ui"))) {
            return null;
        }
        Set<String> allowed = PARAMETERS.getOrDefault(path, Set.of());
        if (parameters.keySet().stream().anyMatch(key -> !allowed.contains(key))) {
            return null;
        }
        if (PAGES.containsKey(path)) {
            return "/query.do".equals(path) && sessionQuery ? null : root + PAGES.get(path);
        }
        String target = switch (path) {
            case "/report.do" -> report(parameters, resolver);
            case "/template.do" -> named("/templates/", value(parameters, "name"));
            case "/bagDetails.do" -> list(parameters);
            case "/loadQuery.do" -> query(parameters);
            default -> null;
        };
        if (target != null && target.length() > 8192) {
            throw new IllegalArgumentException("The encoded entry link exceeds 8192 characters");
        }
        return target;
    }

    private String report(Map<String, String[]> parameters, ReportTypeResolver resolver)
            throws ObjectStoreException {
        String value = value(parameters, "id");
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) {
            throw new IllegalArgumentException("A positive object ID is required");
        }
        int id = Integer.parseInt(value);
        String type = resolver.resolve(id);
        if (type == null) {
            throw new NoSuchElementException("The requested report does not exist");
        }
        return root + "/report/" + encode(type) + "/" + id;
    }

    private String list(Map<String, String[]> parameters) {
        String bagName = value(parameters, "bagName");
        String name = value(parameters, "name");
        if (bagName != null && name != null) {
            throw new IllegalArgumentException("Specify only one list name");
        }
        return named("/results/", bagName == null ? name : bagName);
    }

    private String named(String path, String name) {
        if (name == null) {
            return null;
        }
        if (name.isBlank() || ".".equals(name) || "..".equals(name) || name.length() > 1024) {
            throw new IllegalArgumentException("The resource name is invalid");
        }
        return root + path + encode(name);
    }

    private String query(Map<String, String[]> parameters) {
        if (!"xml".equals(value(parameters, "method"))) {
            return null;
        }
        String xml = value(parameters, "query");
        if (xml == null || xml.isBlank() || xml.length() > 8192) {
            throw new IllegalArgumentException(
                    "An XML query of at most 8192 characters is required");
        }
        String target = root + "/querybuilder?query=" + encode(xml);
        return "true".equalsIgnoreCase(value(parameters, "skipBuilder"))
                ? target + "&run=true" : target;
    }

    private static String value(Map<String, String[]> parameters, String key) {
        String[] values = parameters.get(key);
        if (values == null) {
            return null;
        }
        if (values.length != 1 || values[0] == null) {
            throw new IllegalArgumentException("Link parameters must have exactly one value");
        }
        return values[0];
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
