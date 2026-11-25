plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Build against the installed Rider, no SDK download
        local("/Applications/Rider.app")

        // Bundled with Rider: Git, GitHub account, task management (TaskManager), terminal
        bundledPlugins("Git4Idea", "org.jetbrains.plugins.github", "com.intellij.platform.tasks", "org.jetbrains.plugins.terminal")
    }
}

val pluginSinceBuild = "263"

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = pluginSinceBuild
        }
    }
    buildSearchableOptions = false
}

// Builds the plugin and puts the ZIP into deploy/, replacing an older ZIP there.
// It also writes deploy/updatePlugins.xml, the custom plugin repository for Rider. It points to the ZIP of the release.
// Both files are committed. The release workflow attaches the ZIP to the release without building anything itself.
tasks.register<Sync>("deploy") {
    from(tasks.named("buildPlugin"))
    into(layout.projectDirectory.dir("deploy"))

    // Local values only: the configuration cache cannot store references to the build script
    val version = project.version.toString()
    val updatePluginsFile = layout.projectDirectory.file("deploy/updatePlugins.xml").asFile
    val updatePlugins = """
        <?xml version="1.0" encoding="UTF-8"?>
        <plugins>
          <plugin id="de.toffano.issuemanager"
                  url="https://github.com/MToffano/de.toffano.issuemanager/releases/download/v$version/IssueManager-$version.zip"
                  version="$version">
            <idea-version since-build="$pluginSinceBuild"/>
            <name>IssueManager</name>
            <vendor url="https://github.com/MToffano/de.toffano.issuemanager">Marco Toffano</vendor>
            <description>GitHub issues of the open repository in Rider, linked to branches and worktrees.</description>
          </plugin>
        </plugins>
        """.trimIndent() + "\n"

    inputs.property("updatePlugins", updatePlugins)
    doLast {
        updatePluginsFile.writeText(updatePlugins)
    }
}
