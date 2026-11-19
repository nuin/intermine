package org.intermine.util;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import junit.framework.TestCase;

import org.intermine.metadata.TypeUtil;
import org.intermine.metadata.Util;
import org.intermine.model.FastPathObject;
import org.intermine.objectstore.intermine.NotXmlParser;
import org.intermine.objectstore.intermine.NotXmlRenderer;

public class DynamicProxyCompatibilityTest extends TestCase
{
    public interface Handled extends FastPathObject {
        String getHandler();
        void setHandler(String value);
    }

    public interface Named extends FastPathObject {
        String getName();
        void setName(String value);
    }

    public static class HandlerBean {
        private String handler;

        public String getHandler() {
            return handler;
        }

        public void setHandler(String value) {
            handler = value;
        }
    }

    public abstract static class Constructed implements FastPathObject {
        public Constructed() {
            setFieldValue("value", "defaulted");
        }

        public abstract String getValue();
    }

    public void testConcreteHandlerProperty() throws Exception {
        HandlerBean bean = new HandlerBean();
        TypeUtil.getSetter(HandlerBean.class, "handler").invoke(bean, "worker");
        assertEquals("worker", TypeUtil.getFieldValue(bean, "handler"));
        assertEquals(Collections.singleton("handler"),
                TypeUtil.getFieldInfos(HandlerBean.class).keySet());
    }

    public void testDynamicHandlerProperty() throws Exception {
        Handled bean = (Handled) DynamicBean.create(null, new Class<?>[] {Handled.class});
        TypeUtil.getSetter(bean.getClass(), "handler").invoke(bean, "worker");
        assertEquals("worker", bean.getHandler());
        assertEquals(Collections.singleton("handler"),
                TypeUtil.getFieldInfos(bean.getClass()).keySet());
        assertEquals(Collections.singleton(Handled.class), Util.decomposeClass(bean.getClass()));
        assertEquals(NotXmlParser.DELIM + Handled.class.getName()
                + NotXmlParser.DELIM + "ahandler" + NotXmlParser.DELIM + "worker",
                NotXmlRenderer.render(bean).toString());
        assertEquals(Collections.singleton(Handled.class),
                CollectionUtil.findCommonSuperclasses(Arrays.<Class<?>>asList(bean.getClass(),
                        DynamicBean.create(null, new Class<?>[] {Handled.class, Named.class})
                                .getClass())));
    }

    public void testCachedHandlerProperty() {
        Handled first = DynamicUtil.simpleCreateObject(Handled.class);
        first.setHandler("first");
        Handled second = DynamicUtil.simpleCreateObject(Handled.class);
        second.setHandler("second");
        assertEquals("first", first.getHandler());
        assertEquals("second", second.getHandler());
    }

    public void testProxyGetters() throws Exception {
        Named bean = DynamicUtil.simpleCreateObject(Named.class);
        Method[] getters = TypeUtil.getGetters(bean.getClass());
        assertEquals(1, getters.length);
        assertEquals("getName", getters[0].getName());
    }

    public void testConstructorValues() {
        Constructed bean = (Constructed) DynamicBean.create(Constructed.class,
                new Class<?>[0]);
        assertEquals("defaulted", bean.getValue());
    }

    public void testDirectCreationReusesClass() {
        FastPathObject first = DynamicBean.create(null, new Class<?>[] {Named.class});
        FastPathObject second = DynamicBean.create(null, new Class<?>[] {Named.class});
        assertSame(first.getClass(), second.getClass());
    }

    public void testCachedConstructorValues() {
        HashSet<Class<?>> classes = new HashSet<Class<?>>(Arrays.<Class<?>>asList(
                Constructed.class, Named.class));
        Constructed first = (Constructed) DynamicUtil.createObject(classes);
        first.setFieldValue("value", "changed");
        Constructed second = (Constructed) DynamicUtil.createObject(classes);
        assertEquals(first.getClass(), second.getClass());
        assertEquals("changed", first.getValue());
        assertEquals("defaulted", second.getValue());
    }

    public void testNumericPrimitiveCoercion() {
        DynamicBeanTest.PrimitiveValues bean = (DynamicBeanTest.PrimitiveValues)
                DynamicBean.create(null, new Class<?>[] {DynamicBeanTest.PrimitiveValues.class});
        for (String field : Arrays.asList("byteValue", "shortValue", "intValue", "longValue",
                "floatValue", "doubleValue")) {
            bean.setFieldValue(field, Integer.valueOf(6));
        }
        assertEquals((byte) 6, bean.getByteValue());
        assertEquals((short) 6, bean.getShortValue());
        assertEquals(6, bean.getIntValue());
        assertEquals(6L, bean.getLongValue());
        assertEquals(6.0f, bean.getFloatValue(), 0.0f);
        assertEquals(6.0d, bean.getDoubleValue(), 0.0d);
    }
}
