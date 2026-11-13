plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Build against the installed Rider, no SDK download
        local("/Applications/Rider.app")

        // Bundled with Rider: Git, GitHub account, task management (TaskManager)
        bundledPlugins("Git4Idea", "org.jetbrains.plugins.github", "com.intellij.platform.tasks")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
        }
    }
    buildSearchableOptions = false
}

// Builds the plugin and puts the ZIP into deploy/, replacing an older ZIP there.
// The file is committed. The release workflow attaches it to the release without building anything itself.
tasks.register<Sync>("deploy") {
    from(tasks.named("buildPlugin"))
    into(layout.projectDirectory.dir("deploy"))
}
