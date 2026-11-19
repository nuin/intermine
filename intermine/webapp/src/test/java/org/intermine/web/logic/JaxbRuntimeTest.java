package org.intermine.web.logic;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.annotation.XmlRootElement;

import junit.framework.TestCase;

public class JaxbRuntimeTest extends TestCase
{
    @XmlRootElement
    public static class BindingValue {
        public String name;
    }

    public void testXmlRoundTrip() throws Exception {
        JAXBContext context = JAXBContext.newInstance(BindingValue.class);
        BindingValue original = new BindingValue();
        original.name = "InterMine";
        StringWriter xml = new StringWriter();
        context.createMarshaller().marshal(original, xml);
        BindingValue restored = (BindingValue) context.createUnmarshaller()
                .unmarshal(new StringReader(xml.toString()));
        assertEquals(original.name, restored.name);
    }
}
