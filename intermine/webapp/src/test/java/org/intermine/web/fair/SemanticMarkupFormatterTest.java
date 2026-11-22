package org.intermine.web.fair;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;

import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;

import org.intermine.api.InterMineAPI;
import org.intermine.model.InterMineObject;
import org.intermine.model.testmodel.Employee;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.proxy.ProxyCollection;
import org.intermine.objectstore.proxy.ProxyReference;
import org.intermine.web.context.InterMineContext;
import org.intermine.web.logic.ClassResourceOpener;
import org.intermine.web.logic.config.WebConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SemanticMarkupFormatterTest {
    private Properties properties;

    @Before
    public void setUp() throws Exception {
        ObjectStore store = createMock(ObjectStore.class);
        Organism organism = new Organism(3, Map.of("taxonId", "9606"));
        ProxyReference organismRef = new ProxyReference(store, 3, InterMineObject.class);
        Protein protein = new Protein(2, Map.of("primaryAccession", "P12345",
                "primaryIdentifier", "P12345", "organism", organismRef));
        ProxyCollection proteins = createMock(ProxyCollection.class);
        expect(proteins.iterator()).andAnswer(() -> java.util.List.of(protein).iterator())
                .anyTimes();
        replay(proteins);
        Gene gene = new Gene(1, Map.of("primaryIdentifier", "GENE1", "symbol", "G1",
                "description", "Fixture gene", "organism", organismRef, "proteins", proteins));
        Map<Integer, InterMineObject> objects = Map.of(1, gene, 2, protein, 3, organism);
        expect(store.getObjectById(anyInt())).andAnswer(
                () -> objects.get(getCurrentArgument(0))).anyTimes();
        expect(store.getObjectById(anyInt(), eq(InterMineObject.class))).andAnswer(
                () -> objects.get(getCurrentArgument(0))).anyTimes();
        replay(store);
        InterMineAPI api = createMock(InterMineAPI.class);
        expect(api.getObjectStore()).andReturn(store).anyTimes();
        replay(api);
        properties = new Properties();
        properties.setProperty("markup.webpages.enable", "true");
        InterMineContext.initialise(api, properties, createMock(WebConfig.class),
                new ClassResourceOpener(getClass()));
    }

    @After
    public void tearDown() {
        InterMineContext.doShutdown();
    }

    @Test
    public void directReportUsesItsOwnBaseForGeneProteinAndOrganism() {
        assertUrls("https://first.example/mine/", report("first.example", "/mine"));
        assertUrls("https://second.example/other/", report("second.example", "/other"));
    }

    @Test
    public void configuredIdentifierBaseAppliesWithoutVisitingHome() {
        properties.setProperty("identifier.uri.base", "https://identifiers.example/catalog");
        assertUrls("https://identifiers.example/catalog/", report("first.example", "/mine"));
    }

    private Map<String, Object> report(String host, String path) {
        HttpServletRequest request = createMock(HttpServletRequest.class);
        expect(request.getScheme()).andStubReturn("https");
        expect(request.getServerName()).andStubReturn(host);
        expect(request.getServerPort()).andStubReturn(443);
        expect(request.getContextPath()).andStubReturn(path);
        replay(request);
        return SemanticMarkupFormatter.formatBioEntity(request, 1);
    }

    private void assertUrls(String base, Map<String, Object> markup) {
        assertEquals(base + "gene:GENE1", markup.get("@id"));
        assertEquals(base + "gene:GENE1", markup.get("url"));
        assertEquals("gene:GENE1", markup.get("identifier"));
        assertEquals(base + "organism:9606", markup.get("taxonomicRange"));
        Map<?, ?> protein = (Map<?, ?>) markup.get("encodesBioChemEntity");
        assertEquals(base + "protein:P12345", protein.get("@id"));
        assertEquals(base + "organism:9606", protein.get("taxonomicRange"));
    }

    private static class MetadataObject extends Employee {
        private final Map<String, Object> fields;

        MetadataObject(int id, Map<String, Object> fields) {
            setId(id);
            this.fields = fields;
        }

        @Override
        public Object getFieldValue(String field) {
            return fields.get(field);
        }

        @Override
        public Object getFieldProxy(String field) {
            return fields.get(field);
        }
    }

    private static class Gene extends MetadataObject {
        Gene(int id, Map<String, Object> fields) {
            super(id, fields);
        }
    }

    private static class Protein extends MetadataObject {
        Protein(int id, Map<String, Object> fields) {
            super(id, fields);
        }
    }

    private static class Organism extends MetadataObject {
        Organism(int id, Map<String, Object> fields) {
            super(id, fields);
        }
    }
}
