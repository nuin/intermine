package org.intermine.web.logic.profile;

import static org.easymock.EasyMock.*;

import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.JspException;

import junit.framework.TestCase;
import org.apache.struts.taglib.html.Constants;
import org.apache.struts.taglib.html.MultiboxTag;
import org.intermine.web.struts.ModifySuperUserForm;

public class AdministrativeCheckboxTest extends TestCase {
    private static class Checkbox extends MultiboxTag {
        String render() throws JspException {
            StringBuffer html = new StringBuffer();
            String rawValue = prepareValue(html);
            prepareChecked(html, rawValue);
            return html.toString();
        }
    }

    private String render(String account, String administrator) throws JspException {
        ModifySuperUserForm form = new ModifySuperUserForm();
        form.setSuperUsers(new String[] {administrator});
        PageContext page = createMock(PageContext.class);
        expect(page.findAttribute(Constants.BEAN_KEY)).andReturn(form);
        replay(page);
        Checkbox checkbox = new Checkbox();
        checkbox.setPageContext(page);
        checkbox.setProperty("superUsers");
        checkbox.setValue(account);
        String result = checkbox.render();
        verify(page);
        return result;
    }

    public void testRawAdministratorNameIsCheckedAndEscapedExactlyOnce() throws Exception {
        assertEquals(" value=\"a&amp;b\" checked=\"checked\"", render("a&b", "a&b"));
    }

    public void testEntitySpelledAccountDoesNotInheritAnotherAccountsRole() throws Exception {
        assertEquals(" value=\"a&amp;amp;b\"", render("a&amp;b", "a&b"));
    }
}
