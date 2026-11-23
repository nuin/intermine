package org.intermine.api.profile;

import java.lang.reflect.Proxy;

import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.ObjectStoreWriter;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class TagManagerCloseTest
{
    @Test
    public void reportsWriterCloseFailure() {
        ObjectStoreException failure = new ObjectStoreException("Cannot close writer");
        ObjectStoreWriter writer = (ObjectStoreWriter) Proxy.newProxyInstance(
                ObjectStoreWriter.class.getClassLoader(),
                new Class<?>[] {ObjectStoreWriter.class}, (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        throw failure;
                    }
                    throw new AssertionError("Unexpected writer call: " + method.getName());
                });
        try {
            new TagManager(writer).close();
            fail("Writer close failure must be reported");
        } catch (ObjectStoreException e) {
            assertSame(failure, e);
        }
    }
}
