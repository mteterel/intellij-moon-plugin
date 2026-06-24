package dev.mteterel.moonintellij.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.mteterel.moonintellij.services.MyProjectService

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.service<MyProjectService>()
    }
}
