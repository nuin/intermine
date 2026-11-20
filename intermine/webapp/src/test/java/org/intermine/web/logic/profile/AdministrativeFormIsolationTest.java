package org.intermine.web.logic.profile;

import static org.easymock.EasyMock.*;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import junit.framework.TestCase;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.RequestProcessor;
import org.apache.struts.config.FormBeanConfig;
import org.apache.struts.config.ModuleConfig;
import org.apache.struts.config.impl.ModuleConfigImpl;
import org.intermine.web.logic.Constants;
import org.intermine.web.struts.EditPropertiesAction;
import org.intermine.web.struts.EditPropertiesForm;

public class AdministrativeFormIsolationTest extends TestCase {
    private static class Forms extends RequestProcessor {
        Forms(ModuleConfig config) {
            moduleConfig = config;
        }

        EditPropertiesForm create(HttpServletRequest request, HttpServletResponse response,
                ActionMapping mapping) {
            return (EditPropertiesForm) processActionForm(request, response, mapping);
        }
    }

    private ActionMapping propertyMapping() throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream input = getClass().getResourceAsStream("/web-config/struts-config.xml")) {
            assertNotNull("Production Struts mappings must be on the test classpath", input);
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && "action".equals(reader.getLocalName())
                            && "/editProperty".equals(reader.getAttributeValue(null, "path"))) {
                        ActionMapping mapping = new ActionMapping();
                        mapping.setPath("/editProperty");
                        mapping.setName(reader.getAttributeValue(null, "name"));
                        String scope = reader.getAttributeValue(null, "scope");
                        if (scope != null) {
                            mapping.setScope(scope);
                        }
                        mapping.addForwardConfig(new ActionForward("success", "/showProperties.do", false));
                        return mapping;
                    }
                }
            } finally {
                reader.close();
            }
        }
        throw new AssertionError("Missing editProperty mapping");
    }

    public void testOverlappingRejectedRequestCannotReplaceAcceptedPropertyPair() throws Exception {
        HttpTestSupport first = new HttpTestSupport();
        first.attributes.put(Constants.PROFILE, HttpTestSupport.profile(true));
        first.attributes.put(AdminAccess.TOKEN_ATTRIBUTE, "valid-token");
        first.parameters.put("adminToken", "valid-token");
        Properties properties = new Properties();
        properties.setProperty("allowed", "before");
        properties.setProperty("forbidden", "before");
        first.contextAttributes.put(Constants.WEB_PROPERTIES, properties);
        first.contextAttributes.put(Constants.PROPERTIES_ORIGINS, new HashMap<String, List<String>>());

        Map<String, Object> secondAttributes = new HashMap<>();
        HttpServletRequest second = createMock(HttpServletRequest.class);
        expect(second.getSession()).andStubReturn(first.session);
        expect(second.getMethod()).andStubReturn("POST");
        expect(second.getParameter("adminToken")).andStubReturn("wrong-token");
        expect(second.getAttribute(anyString())).andAnswer(() ->
                secondAttributes.get(getCurrentArgument(0))).anyTimes();
        second.setAttribute(anyString(), anyObject());
        expectLastCall().andAnswer(() -> {
            secondAttributes.put(getCurrentArgument(0), getCurrentArgument(1));
            return null;
        }).anyTimes();
        replay(second);

        ActionMapping mapping = propertyMapping();
        ModuleConfig module = new ModuleConfigImpl("");
        FormBeanConfig bean = new FormBeanConfig();
        bean.setName(mapping.getName());
        bean.setType(EditPropertiesForm.class.getName());
        module.addFormBeanConfig(bean);
        Forms processor = new Forms(module);
        EditPropertiesForm accepted = processor.create(first.request, first.response, mapping);
        accepted.setPropertyName("allowed");
        accepted.setPropertyValue("accepted");
        EditPropertiesForm rejected = processor.create(second, first.response, mapping);
        rejected.reset(mapping, second);
        rejected.setPropertyName("forbidden");
        rejected.setPropertyValue("rejected");
        EditPropertiesAction action = new EditPropertiesAction();
        assertNull(action.execute(mapping, rejected, second, first.response));
        assertEquals(403, first.status);
        assertNotNull(action.execute(mapping, accepted, first.request, first.response));
        assertEquals("accepted", properties.getProperty("allowed"));
        assertEquals("before", properties.getProperty("forbidden"));
        assertNotSame(accepted, rejected);
    }
}
