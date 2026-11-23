package org.intermine.util;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import javassist.util.proxy.MethodFilter;
import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.Proxy;
import javassist.util.proxy.ProxyFactory;

import org.intermine.metadata.StringUtil;
import org.intermine.metadata.TypeUtil;
import org.intermine.metadata.Util;
import org.intermine.model.FastPathObject;
import org.intermine.model.InterMineObject;
import org.intermine.objectstore.intermine.NotXmlRenderer;
import org.intermine.objectstore.proxy.ProxyReference;

/**
 * Class which represents a generic bean
 * @author Andrew Varley
 */
public class DynamicBean implements MethodHandler
{

    private static final class FinalizeFilter implements MethodFilter
    {

        @Override
        public boolean isHandled(Method method) {
            return !("finalize".equals(method.getName())
                    && method.getParameterTypes().length == 0
                    && method.getReturnType() == Void.TYPE);
        }

    }

    private static final MethodFilter FINALIZE_FILTER = new FinalizeFilter();
    private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = createPrimitiveDefaults();
    private static final Map<List<Class<?>>, Class<? extends FastPathObject>> PROXY_CLASSES
        = new HashMap<List<Class<?>>, Class<? extends FastPathObject>>();
    private static final MethodHandler INITIALIZER = (obj, method, proceed, args) -> {
        DynamicBean bean = new DynamicBean();
        ((Proxy) obj).setHandler(bean);
        return bean.invoke(obj, method, proceed, args);
    };
    //private static final Logger LOG = Logger.getLogger(DynamicBean.class);
    private Map<String, Object> map = new HashMap<String, Object>();

    /**
     * Construct the interceptor
     */
    public DynamicBean() {
        // empty
    }

    /**
     * Create a DynamicBean
     *
     * @param clazz the class to extend
     * @param inter the interfaces to implement
     * @return the DynamicBean
     */
    public static FastPathObject create(Class<? extends FastPathObject> clazz, Class<?> [] inter) {
        if ((clazz != null) && clazz.isInterface()) {
            throw new IllegalArgumentException("clazz must not be an interface");
        }
        return DynamicUtil.createObject(getProxyClass(clazz, inter));
    }

    private static synchronized Class<? extends FastPathObject> getProxyClass(
            Class<? extends FastPathObject> clazz, Class<?>[] inter) {
        List<Class<?>> key = new ArrayList<Class<?>>();
        key.add(clazz);
        if (inter != null) {
            key.addAll(Arrays.asList(inter));
        }
        Class<? extends FastPathObject> proxyClass = PROXY_CLASSES.get(key);
        if (proxyClass == null) {
            ProxyFactory factory = new ProxyFactory();
            factory.setSuperclass(clazz);
            factory.setInterfaces(inter);
            factory.setFilter(FINALIZE_FILTER);
            factory.setUseWriteReplace(false);
            // Constructor callbacks need a handler before super(); this disables factory caching.
            factory.setHandler(INITIALIZER);
            proxyClass = factory.createClass().asSubclass(FastPathObject.class);
            PROXY_CLASSES.put(key, proxyClass);
        }
        return proxyClass;
    }

    /**
     * Intercept all method calls, and operate on Map.
     * Note that final methods (eg. getClass) cannot be intercepted
     *
     * @param obj the proxy
     * @param method the method called
     * @param args the parameters
     * @param proxy the method proxy
     * @return the return value of the real method call
     * @throws Throwable if an error occurs in executing the real method
     */
    @Override
    public Object invoke(Object obj, Method method, Method proxy, Object[] args) throws Throwable {
        Object result = invokeModelMethod(obj, method, proxy, args);
        Class<?> type = method.getReturnType();
        if (result == null) {
            return PRIMITIVE_DEFAULTS.get(type);
        }
        if (type.isPrimitive() && result instanceof Number) {
            return convertNumber((Number) result, type);
        }
        return result;
    }

    private static Number convertNumber(Number value, Class<?> type) {
        if (type == Byte.TYPE) {
            return Byte.valueOf(value.byteValue());
        } else if (type == Short.TYPE) {
            return Short.valueOf(value.shortValue());
        } else if (type == Integer.TYPE) {
            return Integer.valueOf(value.intValue());
        } else if (type == Long.TYPE) {
            return Long.valueOf(value.longValue());
        } else if (type == Float.TYPE) {
            return Float.valueOf(value.floatValue());
        } else if (type == Double.TYPE) {
            return Double.valueOf(value.doubleValue());
        }
        return value;
    }

    private static Map<Class<?>, Object> createPrimitiveDefaults() {
        Map<Class<?>, Object> defaults = new HashMap<Class<?>, Object>();
        for (Class<?> type : new Class<?>[] {Boolean.TYPE, Byte.TYPE, Character.TYPE, Short.TYPE,
            Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE}) {
            defaults.put(type, Array.get(Array.newInstance(type, 1), 0));
        }
        return Collections.unmodifiableMap(defaults);
    }

    private Object invokeModelMethod(Object obj, Method method, Method proxy, Object[] args)
        throws Throwable {
        // java.lang.Object methods
        if ("equals".equals(method.getName())) {
            if (args[0] instanceof InterMineObject) {
                Integer otherId = ((InterMineObject) args[0]).getId();
                Integer thisId = (Integer) map.get("id");
                return Boolean.valueOf(thisId != null ? thisId.equals(otherId) : obj == args[0]);
            }
            return Boolean.FALSE;
        }
        if ("hashCode".equals(method.getName())) {
            return map.get("id");
        }
        if ("toString".equals(method.getName())) {
            return doToString(obj);
        }
        if ("getoBJECT".equals(method.getName()) && (args.length == 0)) {
            return NotXmlRenderer.render(obj);
        }
        if ("getFieldValue".equals(method.getName()) && (args.length == 1)) {
            return handleGetFieldValue(obj, method, args);
        }
        if ("getFieldProxy".equals(method.getName()) && (args.length == 1)) {
            return handleGetFieldProxy(obj, args);
        }
        if ("setFieldValue".equals(method.getName()) && (args.length == 2)
                && (method.getReturnType() == Void.TYPE)) {
            String fieldName = (String) args[0];
            map.put(fieldName, args[1]);
            return null;
        }
        if ("addCollectionElement".equals(method.getName()) && (args.length == 2)
                && (method.getReturnType() == Void.TYPE)) {
            String fieldName = (String) args[0];
            @SuppressWarnings("unchecked") Collection<Object> col = (Collection<Object>) map
                .get(fieldName);
            if (col == null) {
                col = new HashSet<Object>();
                map.put(fieldName, col);
            }
            col.add(args[1]);
            return null;
        }
        if ("getFieldType".equals(method.getName()) && (args.length == 1)) {
            try {
                String methodName = "get" + StringUtil.reverseCapitalisation((String) args[0]);
                Method getMethod = obj.getClass().getMethod(methodName);
                return getMethod.getReturnType();
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("No such field " + args[0], e);
            }
        }
        if ("getElementType".equals(method.getName()) && (args.length == 1)) {
            return getElementType(obj, (String) args[0]);
        }
        // Bean methods
        if (method.getName().startsWith("get") && (args.length == 0)) {
            return handleGet(method);
        }
        if (method.getName().startsWith("is")
            && (args.length == 0)) {
            return map.get(StringUtil.reverseCapitalisation(method.getName().substring(2)));
        }
        if (method.getName().startsWith("set") && (args.length == 1)
                && (method.getReturnType() == Void.TYPE)) {
            map.put(StringUtil.reverseCapitalisation(method.getName().substring(3)), args[0]);
            return null;
        }
        if (method.getName().startsWith("proxy") && (args.length == 1)
                && (method.getReturnType() == Void.TYPE)) {
            map.put(StringUtil.reverseCapitalisation(method.getName().substring(5)), args[0]);
            return null;
        }
        if (method.getName().startsWith("proxGet") && (args.length == 0)) {
            return map.get(StringUtil.reverseCapitalisation(method.getName().substring(7)));
        }
        if (method.getName().startsWith("add") && (args.length == 1)
                && (method.getReturnType() == Void.TYPE)) {
            return handleAddObject(method, args);
        }
        try {
            return proxy.invoke(obj, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Class<?> getElementType(Object obj, String fieldName) {
        String methodName = "add" + StringUtil.reverseCapitalisation(fieldName);
        for (Method addMethod : obj.getClass().getMethods()) {
            if (addMethod.getName().equals(methodName)) {
                return addMethod.getParameterTypes()[0];
            }
        }
        throw new RuntimeException("No such collection " + fieldName);
    }

    private Object handleGetFieldValue(Object obj, Method method, Object[] args) {
        String fieldName = (String) args[0];
        Object retval = map.get(fieldName);
        if (retval instanceof ProxyReference) {
            try {
                retval = ((ProxyReference) retval).getObject();
            } catch (NullPointerException e) {
                NullPointerException e2 = new NullPointerException("Exception while calling "
                        + method.getName() + "(\"" + args[0] + "\") on object with ID "
                        + map.get("id"));
                e2.initCause(e);
                throw e2;
            } catch (Exception e) {
                RuntimeException e2 = new RuntimeException("Exception while calling "
                        + method.getName() + "(\"" + args[0] + "\") on object with ID "
                        + map.get("id"));
                e2.initCause(e);
                throw e2;
            }
        }
        if (retval == null) {
            Class<?> fieldType = null;
            try {
                String methodName = "get" + StringUtil.reverseCapitalisation((String) args[0]);
                Method getMethod = obj.getClass().getMethod(methodName);
                fieldType = getMethod.getReturnType();
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("No such field " + args[0], e);
            }

            if (Collection.class.isAssignableFrom(fieldType)) {
                retval = new HashSet<Object>();
                map.put(fieldName, retval);
            }
            if (fieldType.isPrimitive()) {
                if (Boolean.TYPE.equals(fieldType)) {
                    retval = Boolean.FALSE;
                } else if (Short.TYPE.equals(fieldType)) {
                    retval = new Short((short) 0);
                } else if (Integer.TYPE.equals(fieldType)) {
                    retval = new Integer(0);
                } else if (Long.TYPE.equals(fieldType)) {
                    retval = new Long(0);
                } else if (Float.TYPE.equals(fieldType)) {
                    retval = new Float(0.0);
                } else if (Double.TYPE.equals(fieldType)) {
                    retval = new Double(0.0);
                }
                map.put(fieldName, retval);
            }
        }
        return retval;
    }

    private Object handleGetFieldProxy(Object obj, Object[] args) {
        String fieldName = (String) args[0];
        Object retval = map.get(fieldName);
        if (retval == null) {
            Class<?> fieldType = null;
            try {
                String methodName = "get" + StringUtil.reverseCapitalisation((String) args[0]);
                Method getMethod = obj.getClass().getMethod(methodName);
                fieldType = getMethod.getReturnType();
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("No such field " + args[0], e);
            }

            if (Collection.class.isAssignableFrom(fieldType)) {
                retval = new HashSet<Object>();
                map.put(fieldName, retval);
            }
            if (fieldType.isPrimitive()) {
                if (Boolean.TYPE.equals(fieldType)) {
                    retval = Boolean.FALSE;
                } else if (Short.TYPE.equals(fieldType)) {
                    retval = new Short((short) 0);
                } else if (Integer.TYPE.equals(fieldType)) {
                    retval = new Integer(0);
                } else if (Long.TYPE.equals(fieldType)) {
                    retval = new Long(0);
                } else if (Float.TYPE.equals(fieldType)) {
                    retval = new Float(0.0);
                } else if (Double.TYPE.equals(fieldType)) {
                    retval = new Double(0.0);
                }
                map.put(fieldName, retval);
            }
        }
        return retval;
    }

    private Object handleGet(Method method) {
        Object retval = map.get(StringUtil.reverseCapitalisation(method.getName()
                    .substring(3)));
        if (retval instanceof ProxyReference) {
            try {
                retval = ((ProxyReference) retval).getObject();
            } catch (NullPointerException e) {
                NullPointerException e2 = new NullPointerException("Exception while calling "
                        + method.getName() + " on object with ID " + map.get("id"));
                e2.initCause(e);
                throw e2;
            } catch (Exception e) {
                RuntimeException e2 = new RuntimeException("Exception while calling "
                        + method.getName() + " on object with ID " + map.get("id"));
                e2.initCause(e);
                throw e2;
            }
        }
        if ((retval == null) && Collection.class.isAssignableFrom(method.getReturnType())) {
            retval = new HashSet<Object>();
            map.put(StringUtil.reverseCapitalisation(method.getName().substring(3)), retval);
        }
        return retval;
    }

    private Object handleAddObject(Method method, Object[] args) {
        String key = StringUtil.reverseCapitalisation(method.getName().substring(3));
        @SuppressWarnings("unchecked")
        Collection<Object> col = (Collection<Object>) map.get(key);
        if (col == null) {
            col = new HashSet<Object>();
            map.put(key, col);
        }
        col.add(args[0]);
        return null;
    }

    private String doToString(Object obj) {
        StringBuffer className = new StringBuffer();
        boolean needComma = false;
        Set<Class<?>> classes = Util.decomposeClass(obj.getClass());
        for (Class<?> clazz : classes) {
            if (needComma) {
                className.append(",");
            }
            needComma = true;
            className.append(TypeUtil.unqualifiedName(clazz.getName()));
        }
        StringBuffer retval = new StringBuffer(className.toString() + " [");
        Map<String, Object> sortedMap = new TreeMap<String, Object>(map);
        needComma = false;
        for (Map.Entry<String, Object> mapEntry : sortedMap.entrySet()) {
            String fieldName = mapEntry.getKey();
            Object fieldValue = mapEntry.getValue();
            if (!(fieldValue instanceof Collection<?>)) {
                if (needComma) {
                    retval.append(", ");
                }
                needComma = true;
                if (fieldValue instanceof ProxyReference) {
                    retval.append(fieldName + "=" + ((ProxyReference) fieldValue).getId());
                } else if (fieldValue instanceof InterMineObject) {
                    retval.append(fieldName + "=" + ((InterMineObject) fieldValue).getId());
                } else {
                    retval.append(fieldName + "=\"" + fieldValue + "\"");
                }
            }
        }
        return retval.toString() + "]";
    }

    /**
     * Getter for the map, for testing purposes
     *
     * @return a map of data for this object
     */
    public Map<String, Object> getMap() {
        return map;
    }
}
