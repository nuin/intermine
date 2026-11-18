package org.intermine.plugin.dbmodel

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertTrue

class DBModelPluginTest {
    @Test
    void modelTasksHaveAResolvableConfigurationSeparateFromLibraryApi() {
        def project = ProjectBuilder.builder().build()
        project.pluginManager.apply('java-library')

        new DBModelPlugin().apply(project)

        def modelApi = project.configurations.findByName('intermineApi')
        assertNotNull(modelApi)
        assertTrue(modelApi.canBeResolved)
        assertFalse(modelApi.canBeConsumed)
        assertFalse(project.configurations.api.canBeResolved)
    }
}
