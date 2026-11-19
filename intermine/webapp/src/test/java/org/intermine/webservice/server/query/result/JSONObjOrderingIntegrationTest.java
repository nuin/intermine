package org.intermine.webservice.server.query.result;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import junit.framework.TestCase;
import org.easymock.EasyMock;
import org.intermine.api.bag.BagManager;
import org.intermine.api.query.PathQueryExecutor;
import org.intermine.metadata.Model;
import org.intermine.model.InterMineObject;
import org.intermine.model.testmodel.Company;
import org.intermine.model.testmodel.Department;
import org.intermine.model.testmodel.Employee;
import org.intermine.objectstore.ObjectStoreWriter;
import org.intermine.objectstore.ObjectStoreWriterFactory;
import org.intermine.pathquery.OrderDirection;
import org.intermine.pathquery.OuterJoinStatus;
import org.intermine.pathquery.PathQuery;
import org.intermine.webservice.server.output.JSONResultsIterator;
import org.intermine.util.DynamicUtil;
import org.json.JSONArray;
import org.json.JSONObject;

public class JSONObjOrderingIntegrationTest extends TestCase {
    private ObjectStoreWriter writer;
    private PathQueryExecutor executor;
    private final List<InterMineObject> stored = new ArrayList<InterMineObject>();

    @Override
    protected void setUp() throws Exception {
        writer = ObjectStoreWriterFactory.getObjectStoreWriter("osw.unittest");
        BagManager bags = EasyMock.createMock(BagManager.class);
        EasyMock.expect(bags.getCurrentBags(null)).andReturn(Collections.emptyMap()).anyTimes();
        EasyMock.replay(bags);
        executor = new PathQueryExecutor(writer.getObjectStore(), null, null, bags);
        Department first = department("Same");
        Department second = department("Same");
        employee("Beta", 40, first);
        employee("Zulu", 30, second);
        employee("Alpha", 20, first);
        employee("Gamma", 10, second);
    }

    private Department department(String name) throws Exception {
        Department department = new Department();
        department.setName(name);
        writer.store(department);
        stored.add(department);
        return department;
    }

    private void employee(String name, int age, Department department) throws Exception {
        Employee employee = new Employee();
        employee.setName(name);
        employee.setAge(age);
        employee.setDepartment(department);
        writer.store(employee);
        stored.add(employee);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            for (int i = stored.size() - 1; i >= 0; i--) {
                writer.delete(stored.get(i));
            }
        } finally {
            writer.close();
        }
    }

    public void testDescendingObjectsAndOffset() throws Exception {
        PathQuery query = new PathQuery(Model.getInstanceByName("testmodel"));
        query.addViews("Employee.name");
        query.addOrderBy("Employee.name", OrderDirection.DESC);
        assertEquals(Arrays.asList("Zulu", "Gamma", "Beta", "Alpha"), names(query, 0, 10));
        assertEquals(Arrays.asList("Gamma", "Beta"), names(query, 1, 2));
    }

    public void testReferenceAndSecondarySort() throws Exception {
        PathQuery query = new PathQuery(Model.getInstanceByName("testmodel"));
        query.addViews("Employee.name", "Employee.department.name", "Employee.age");
        query.addOrderBy("Employee.department.name", OrderDirection.DESC);
        query.addOrderBy("Employee.age", OrderDirection.ASC);
        assertEquals(Arrays.asList("Gamma", "Alpha", "Zulu", "Beta"), names(query, 0, 10));
    }

    public void testTiedRootValuesDoNotSplitCollections() throws Exception {
        PathQuery query = new PathQuery(Model.getInstanceByName("testmodel"));
        query.addViews("Department.name", "Department.employees.name");
        query.addOrderBy("Department.name", OrderDirection.ASC);
        query.addOrderBy("Department.employees.name", OrderDirection.DESC);
        List<JSONObject> objects = objects(query, 0, 10);
        assertEquals(2, objects.size());
        assertEquals(Arrays.asList("Beta", "Alpha"), childNames(objects.get(0)));
        assertEquals(Arrays.asList("Zulu", "Gamma"), childNames(objects.get(1)));
    }

    public void testOuterJoinedCollectionStaysInOneObject() throws Exception {
        PathQuery query = new PathQuery(Model.getInstanceByName("testmodel"));
        query.addViews("Department.name", "Department.employees.name");
        query.setOuterJoinStatus("Department.employees", OuterJoinStatus.OUTER);
        query.addOrderBy("Department.id", OrderDirection.DESC);
        List<JSONObject> objects = objects(query, 0, 10);
        assertEquals(2, objects.size());
        assertEquals(2, objects.get(0).getJSONArray("employees").length());
        assertEquals(stored.get(1).getId().intValue(), objects.get(0).getInt("objectId"));
    }

    public void testInterleavedGrandchildrenKeepTheirParent() throws Exception {
        Company company = DynamicUtil.simpleCreateObject(Company.class);
        company.setName("Ordering company");
        writer.store(company);
        stored.add(company);
        for (int i = 0; i < 2; i++) {
            Department department = (Department) stored.get(i);
            department.setName(i == 0 ? "A" : "B");
            department.setCompany(company);
            writer.store(department);
        }
        Employee employee = (Employee) stored.get(2);
        employee.setName("Omega");
        writer.store(employee);
        PathQuery query = new PathQuery(Model.getInstanceByName("testmodel"));
        query.addViews("Company.name", "Company.departments.name",
            "Company.departments.employees.name", "Company.departments.employees.department.name");
        query.addOrderBy("Company.departments.employees.name", OrderDirection.ASC);
        List<JSONObject> objects = objects(query, 0, 10);
        assertEquals(1, objects.size());
        JSONArray departments = objects.get(0).getJSONArray("departments");
        assertEquals(2, departments.length());
        assertEquals(Arrays.asList("Alpha", "Omega"), childNames(departments.getJSONObject(0)));
        assertEquals(Arrays.asList("Gamma", "Zulu"), childNames(departments.getJSONObject(1)));
        for (int i = 0; i < departments.length(); i++) {
            JSONObject department = departments.getJSONObject(i);
            assertEquals(stored.get(i).getId().intValue(), department.getInt("objectId"));
            JSONArray employees = department.getJSONArray("employees");
            for (int j = 0; j < employees.length(); j++) {
                assertEquals(department.getInt("objectId"), employees.getJSONObject(j)
                    .getJSONObject("department").getInt("objectId"));
            }
        }
    }

    private List<String> names(PathQuery query, int start, int limit) throws Exception {
        List<String> names = new ArrayList<String>();
        for (JSONObject object : objects(query, start, limit)) {
            names.add(object.getString("name"));
        }
        return names;
    }

    private List<String> childNames(JSONObject object) throws Exception {
        List<String> names = new ArrayList<String>();
        JSONArray children = object.getJSONArray("employees");
        for (int i = 0; i < children.length(); i++) {
            names.add(children.getJSONObject(i).getString("name"));
        }
        return names;
    }

    private List<JSONObject> objects(PathQuery query, int start, int limit) throws Exception {
        JSONResultsIterator iterator = new JSONResultsIterator(executor.execute(
            PathQueryBuilderForJSONObj.processQuery(query), start, limit));
        List<JSONObject> objects = new ArrayList<JSONObject>();
        while (iterator.hasNext()) {
            objects.add(iterator.next());
        }
        return objects;
    }
}
