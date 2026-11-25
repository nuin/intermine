package org.intermine.web.filters;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;

import org.junit.Test;

import static org.junit.Assert.*;

public class PrimaryUiRoutesTest
{
    private final PrimaryUiRoutes routes = new PrimaryUiRoutes("https://mine.example/bluegenes/test");

    private String route(String path, Map<String, String[]> parameters) throws Exception {
        return routes.destination(path, parameters, false, id -> id == 12 ? "Employee" : null);
    }

    private static void fails(Class<? extends Exception> type, Callable<?> action) throws Exception {
        try {
            action.call();
        } catch (Exception error) {
            if (!type.isInstance(error)) {
                throw error;
            }
            return;
        }
        fail("Expected " + type.getSimpleName());
    }

    @Test
    public void homeAndCollectionsUseConfiguredRoot() throws Exception {
        assertEquals("https://mine.example/bluegenes/test", route("/", Map.of()));
        assertEquals("https://mine.example/bluegenes/test", route("/begin.do", Map.of()));
        assertEquals("https://mine.example/bluegenes/test/templates", route("/templates.do", Map.of()));
        assertEquals("https://mine.example/bluegenes/test/upload", route("/bag.do", Map.of()));
        assertEquals("https://mine.example/bluegenes/test/querybuilder", route("/query.do", Map.of()));
        assertNull(new PrimaryUiRoutes("").destination("/", Map.of(), false, id -> null));
    }

    @Test
    public void reportResolvesActualClassAndRejectsAbsentObject() throws Exception {
        assertEquals("https://mine.example/bluegenes/test/report/Employee/12",
                route("/report.do", Map.of("id", new String[] {"12"})));
        fails(NoSuchElementException.class,
                () -> route("/report.do", Map.of("id", new String[] {"13"})));
        fails(IllegalArgumentException.class,
                () -> route("/report.do", Map.of("id", new String[] {"2147483648"})));
        fails(IllegalArgumentException.class,
                () -> route("/report.do", Map.of("id", new String[] {"12", "13"})));
    }

    @Test
    public void namesAreEncodedOnceWithoutChangingTheResource() throws Exception {
        String value = "A/B + β&%";
        assertEquals("https://mine.example/bluegenes/test/results?title=A%2FB%20%2B%20%CE%B2%26%25",
                route("/bagDetails.do", Map.of("bagName", new String[] {value})));
        assertEquals("https://mine.example/bluegenes/test/templates?template=A%2FB%20%2B%20%CE%B2%26%25",
                route("/template.do", Map.of("name", new String[] {value})));
        fails(IllegalArgumentException.class, () -> route("/bagDetails.do",
                Map.of("name", new String[] {"one"}, "bagName", new String[] {"two"})));
        fails(IllegalArgumentException.class,
                () -> route("/bagDetails.do", Map.of("name", new String[] {".."})));
    }

    @Test
    public void xmlQueryAndRunIntentSurviveTheRedirect() throws Exception {
        String query = "<query view=\"Employee.name\"/>";
        assertEquals("https://mine.example/bluegenes/test/querybuilder?query="
                + "%3Cquery%20view%3D%22Employee.name%22%2F%3E&run=true",
                route("/loadQuery.do", Map.of("method", new String[] {"xml"},
                        "query", new String[] {query}, "skipBuilder", new String[] {"true"})));
        fails(IllegalArgumentException.class, () -> route("/loadQuery.do",
                Map.of("method", new String[] {"xml"}, "query", new String[] {"x".repeat(8193)})));
    }

    @Test
    public void encodedQueryCannotExceedBrowserRequestLimits() throws Exception {
        fails(IllegalArgumentException.class, () -> route("/loadQuery.do",
                Map.of("method", new String[] {"xml"}, "query", new String[] {"α".repeat(1500)})));
    }

    @Test
    public void legacyStateExportsAndUnrecognisedParametersStayWithExistingHandlers() throws Exception {
        assertNull(routes.destination("/query.do", Map.of(), true, id -> null));
        assertNull(route("/begin.do", Map.of("ui", new String[] {"legacy"})));
        assertNull(route("/report.do", Map.of("id", new String[] {"12"},
                "token", new String[] {"secret"})));
        assertNull(route("/loadQuery.do", Map.of("method", new String[] {"xml"},
                "query", new String[] {"xml"}, "exportFormat", new String[] {"csv"})));
        assertNull(route("/loadQuery.do", Map.of("method", new String[] {"list"})));
        assertNull(route("/template.do", Map.of("name", new String[] {"A"},
                "constraint1", new String[] {"new value"})));
        assertNull(route("/service/query/results", Map.of()));
    }

    @Test
    public void invalidTargetsCannotBecomeOpenRedirectsOrLoops() throws Exception {
        for (String target : new String[] {"//evil.example/x", "http://evil.example/x",
                "https://user:pass@mine.example/x", "https://mine.example/",
                "https://mine.example/x?return=y", "https://mine.example/x#fragment",
                "https://mine.example/x/../y", "https://mine.example/x/%2e%2e/y",
                "https://mine.example/x//y", "https://mine.example/x;session=abc"}) {
            fails(IllegalArgumentException.class, () -> new PrimaryUiRoutes(target));
        }
        new PrimaryUiRoutes("http://localhost:5016/bluegenes/test/");
        new PrimaryUiRoutes("http://127.0.0.1:5016/test");
    }
}
