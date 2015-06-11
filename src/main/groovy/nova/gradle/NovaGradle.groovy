package nova.gradle

import groovy.transform.CompileStatic
import org.gradle.api.GradleException
import org.gradle.api.GradleScriptException
import org.gradle.api.Plugin
import org.gradle.api.Project

import java.util.regex.Matcher
import java.util.regex.Pattern

class NovaGradle implements Plugin<Project> {

//	public static Project project;

	Pattern taskPattern = Pattern.compile("run(.+)(Server|Client)")

	@Override
	void apply(Project project) {
//		this.project = theProject

		if (!project.plugins.hasPlugin("java")) {
			throw new GradleException("Please apply the java plugin!")
		}

		project.repositories {
			jcenter()
			maven {
				name = "NovaAPI"
				url = "http://maven.novaapi.net/"
			}
		}

		addWrapperTask(project)
	}

	def addWrapperTask(Project project) {
		project.tasks.addRule("Pattern: run<Wrapper>Server") {}
		project.tasks.addRule("Pattern: run<Wrapper>Client") { String taskName ->
			Matcher match = taskPattern.matcher(taskName)
			if (match.matches()) {
				def wrapper = match.group(1)
				def locality = Locality.fromString(match.group(2))

				WrapperManager.get(project, taskName, wrapper, locality, [:])
			}
		}
	}
}
