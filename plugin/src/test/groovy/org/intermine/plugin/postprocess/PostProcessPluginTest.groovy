package org.intermine.plugin.postprocess

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertThrows

class PostProcessPluginTest {
    @Test
    void failedSearchPostprocessFailsTheBuild() {
        def parent = ProjectBuilder.builder().build()
        new File(parent.projectDir, 'project.xml').text = '''
            <project type="bio">
                <post-process name="create-search-index" version="missing-test-version"/>
            </project>
        '''
        def project = ProjectBuilder.builder().withName('postprocess').withParent(parent).build()
        project.pluginManager.apply('java-library')
        project.configurations.create('postProcesses')
        new PostProcessPlugin().apply(project)

        def init = project.tasks.getByName('initPostProcess')
        init.actions.each { it.execute(init) }
        def postprocess = project.tasks.getByName('postProcess')
        def failure = assertThrows(GradleException) {
            postprocess.actions.each { it.execute(postprocess) }
        }
        assertNotNull(failure.cause)
    }
}
