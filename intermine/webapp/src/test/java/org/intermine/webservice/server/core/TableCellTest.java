package org.intermine.webservice.server.core;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;

import org.intermine.metadata.Model;
import org.intermine.model.FastPathObject;
import org.intermine.model.testmodel.Broke;
import org.intermine.model.testmodel.Employee;
import org.intermine.pathquery.Path;
import org.intermine.util.DynamicUtil;
import org.junit.Test;

/**
 * An object made of two classes (here Employee and Broke, in AllianceMine a
 * Gene that is both ProteinCodingGene and NotInSystematicSequenceOfS288C) has
 * no single simple class name. TableCell must still report a type.
 */
public class TableCellTest
{
    private final Model model = Model.getInstanceByName("testmodel");

    private FastPathObject employeeAndBroke() {
        return DynamicUtil.createObject(
                new HashSet<Class<?>>(Arrays.asList(Employee.class, Broke.class)));
    }

    @Test
    public void typeOfTwoClassObjectFollowsTheColumn() throws Exception {
        Path column = new Path(model, "Employee.name");
        TableCell cell = new TableCell(employeeAndBroke(), column, false);
        assertEquals("Employee", cell.getType());
    }

    @Test
    public void typeOfTwoClassObjectWithoutMatchingColumnIsFirstByName() throws Exception {
        Path column = new Path(model, "Broke.debt");
        TableCell cell = new TableCell(employeeAndBroke(), column, false);
        assertEquals("Broke", cell.getType());
        assertEquals(Broke.class, TableCell.typeFor(employeeAndBroke().getClass(), null));
    }

    @Test
    public void typeOfPlainObjectIsItsClass() throws Exception {
        Path column = new Path(model, "Employee.name");
        TableCell cell = new TableCell(new Employee(), column, false);
        assertEquals("Employee", cell.getType());
    }
}
