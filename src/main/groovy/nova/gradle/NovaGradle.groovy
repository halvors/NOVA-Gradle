package nova.gradle

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import nova.gradle.extensions.NovaExtension
import nova.gradle.extensions.WrapperConfigExtension
import nova.gradle.util.FileLogListener
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.tasks.JavaExec
import org.gradle.plugins.ide.idea.model.IdeaModel

import java.util.regex.Pattern

@CompileStatic
class NovaGradle implements Plugin<Project> {

	Pattern taskPattern = Pattern.compile("run(.+)(Server|Client)")

	@Override
	void apply(Project project) {
		//Java check
		def javaversion = Integer.parseInt(System.getProperty("java.runtime.version").split("\\.")[1])
		if (javaversion < 8) {
			throw new GradleException("Please use JDK 8 or above! If this message persists make sure to install all other JDKs.")
		}

		//Set up logging
		def projectCacheDir = project.gradle.startParameter.projectCacheDir ?: new File(project.projectDir, ".gradle")
		FileLogListener.setup(project, new File(projectCacheDir, "gradle.log"))

		//Add IDEA plugin
		project.apply(plugin: "idea")

		//Export function to write to set NOVA dependencies
		project.extensions.extraProperties.set("novaApi", { String version ->
			{ DependencyHandler handler ->
				handler.add("compile", "nova.core:NovaCore:$version:api")
				handler.add("runtime", "nova.core:NovaCore:$version")
			}
		})

		//Nova build extension
		project.extensions.create("nova", NovaExtension, project)

		//Add jcenter and NOVA repos
		project.repositories.with {
			add(jcenter())
			add(maven { MavenArtifactRepository repo ->
				repo.name = "NovaAPI"
				repo.url = "http://maven.novaapi.net/"
			})
		}

		//Add afterEvaluate hook
		project.afterEvaluate(this.&afterEvaluate)
	}

	def afterEvaluate(Project project) {
		//We need the runtime config
		if (!project.configurations.findByName("runtime")) {
			throw new GradleException("Runtime configuration does not exist, make sure you have applied the java, scala or groovy plugins.")
		}

		//Add message to eclipse class
		project.tasks.maybeCreate("eclipse").doFirst {
			project.logger.warn "Creating Eclipse run/debug configurations is not supported, view the NovaGradle docs for information on running and debugging mods in eclipse."
			project.logger.warn "NovaGradle docs are available at http://novaapi.net/docs/Mod%20Development/NOVA%20Gradle/"
		}

		//Add tasks for each wrapper configured
		def nova = project.extensions["nova"] as NovaExtension
		nova.wrappers.each { WrapperConfigExtension wrapper ->
			//TODO: Implement server locality
			Locality locality = Locality.Client

			def configuration = project.configurations.maybeCreate("$wrapper.name-$locality-runtime")
				.extendsFrom(project.configurations["runtime"])

			wrapper.runtime.each {
				project.dependencies.add(configuration.name, it)
			}

			def execTask = addExecTask(project, wrapper, locality, configuration)
			execTask.group = "NOVA Wrapper"
			addIdeaRun(project, execTask.name, "Run \"$wrapper.name\" $locality")
		}
	}

	JavaExec addExecTask(Project project, WrapperConfigExtension wrapper, Locality locality, Configuration config) {
		def task = project.tasks.create("run$wrapper.name$locality", JavaExec).doFirst { JavaExec task ->
			WrapperManager
				.getLaunch(project, wrapper, locality, config)
				.configureJavaExec(project, task)
				.classpath(config)
		} as JavaExec
		task.dependsOn(project.tasks["jar"])

		def runDir = new File(project.rootDir, "run/$wrapper.name/$locality")
		runDir.mkdirs()
		task.workingDir(runDir)
	}

	@CompileStatic(TypeCheckingMode.SKIP)
	def addIdeaRun(Project project, String taskName, String runName) {
		def idea = project.rootProject.extensions["idea"] as IdeaModel
		if (!idea) return //Return if idea is null

		idea.workspace.iws.withXml { XmlProvider xml ->
			def root = xml.asNode()
			def runManager = root.component.find { it.@name == "RunManager"} as Node

			if(runManager.configuration.find { it.@type == "GradleRunConfiguration" && it.@name == runName }) {
				//Already exists
				return
			}

			def relPath = project.rootProject.projectDir.toPath().relativize(project.projectDir.toPath())

			runManager.appendNode("configuration", [default: false, name: runName, type: "GradleRunConfiguration", factoryName: "Gradle"])
				.appendNode("ExternalSystemSettings")
					.appendNode("option", [name: "externalProjectPath", value: "\$PROJECT_DIR\$/${relPath}build.gradle"]).parent()
					.appendNode("option", [name: "externalSystemIdString", value: "GRADLE"]).parent()
					.appendNode("option", [name: "taskNames"])
						.appendNode("list")
							.appendNode("option", [value: taskName])
		}
	}
}
