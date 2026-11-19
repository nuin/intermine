package org.intermine.webservice.server.query.result;

import java.util.Arrays;
import junit.framework.TestCase;
import org.intermine.metadata.Model;
import org.intermine.pathquery.OrderDirection;
import org.intermine.pathquery.OrderElement;
import org.intermine.pathquery.PathQuery;

public class JSONObjOrderingTest extends TestCase {
    private final Model model = Model.getInstanceByName("testmodel");

    public void testDescendingRootAttribute() {
        PathQuery query = new PathQuery(model);
        query.addViews("Employee.name", "Employee.age");
        query.addOrderBy("Employee.name", OrderDirection.DESC);
        PathQuery transformed = PathQueryBuilderForJSONObj.processQuery(query);
        assertEquals(Arrays.asList(new OrderElement("Employee.name", OrderDirection.DESC),
            new OrderElement("Employee.id", OrderDirection.ASC)), transformed.getOrderBy());
        assertEquals(Arrays.asList("Employee.name", "Employee.age"), query.getView());
        assertEquals(1, query.getOrderBy().size());
    }

    public void testMultipleKeysAndReferenceOrder() {
        PathQuery query = new PathQuery(model);
        query.addViews("Employee.department.name", "Employee.age", "Employee.name");
        query.addOrderBy("Employee.department.name", OrderDirection.DESC);
        query.addOrderBy("Employee.age", OrderDirection.ASC);
        PathQuery transformed = PathQueryBuilderForJSONObj.processQuery(query);
        assertEquals(Arrays.asList(
            new OrderElement("Employee.department.name", OrderDirection.DESC),
            new OrderElement("Employee.age", OrderDirection.ASC),
            new OrderElement("Employee.id", OrderDirection.ASC)), transformed.getOrderBy());
    }

    public void testExplicitIdDirectionIsPreservedWithoutDuplicates() {
        PathQuery query = new PathQuery(model);
        query.addViews("Employee.id", "Employee.name");
        query.addOrderBy("Employee.id", OrderDirection.DESC);
        assertEquals(query.getOrderBy(), PathQueryBuilderForJSONObj.processQuery(query).getOrderBy());
    }

    public void testNoExplicitSortUsesRootId() {
        PathQuery query = new PathQuery(model);
        query.addViews("Employee.name");
        assertEquals(Arrays.asList(new OrderElement("Employee.id", OrderDirection.ASC)),
            PathQueryBuilderForJSONObj.processQuery(query).getOrderBy());
    }

    public void testCollectionSortStaysWithinRootObject() {
        PathQuery query = new PathQuery(model);
        query.addViews("Department.name", "Department.employees.name");
        query.addOrderBy("Department.employees.name", OrderDirection.DESC);
        query.addOrderBy("Department.name", OrderDirection.ASC);
        assertEquals(Arrays.asList(
            new OrderElement("Department.name", OrderDirection.ASC),
            new OrderElement("Department.id", OrderDirection.ASC),
            new OrderElement("Department.employees.name", OrderDirection.DESC)),
            PathQueryBuilderForJSONObj.processQuery(query).getOrderBy());
    }

    public void testSimpleClassRetainsItsSort() {
        PathQuery query = new PathQuery(model);
        query.addViews("SimpleObject.name");
        query.addOrderBy("SimpleObject.name", OrderDirection.DESC);
        assertEquals(query.getOrderBy(), PathQueryBuilderForJSONObj.processQuery(query).getOrderBy());
    }
}
