import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.ideamcp"
version = "0.6.8"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation("org.tomlj:tomlj:1.1.1")
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde) else intellijIdea("2026.2.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

tasks.register<Copy>("prepareLocalDependencies") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("local/runtime-dependencies"))
    include("*.jar")
}

// The 262 SDK contains Java 25 classes; keep our own code at Java 21.
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}
tasks.test {
    systemProperty("idea.mcp.test.rg", providers.gradleProperty("rgExecutable").orElse("").get())
    systemProperty("idea.mcp.test.directoryBenchmark", providers.gradleProperty("directoryBenchmark").orElse("false").get())
    systemProperty("idea.mcp.test.directoryQualificationBenchmark", providers.gradleProperty("directoryQualificationBenchmark").orElse("false").get())
    systemProperty("idea.mcp.test.codex", providers.gradleProperty("codexExecutable").orElse("").get())
    systemProperty("idea.mcp.test.baselineJar", providers.gradleProperty("baselinePluginJar").orElse("").get())
    systemProperty("idea.mcp.test.jdk", javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }.get().metadata.installationPath.asFile.absolutePath)
    systemProperty("idea.load.plugins.id", "dev.ideamcp.symbols,com.intellij.java,com.intellij.mcpServer,intellij.debuggerMcp,com.intellij.database,Git4Idea,org.jetbrains.plugins.terminal,org.intellij.qodana,com.jetbrains.sh,org.jetbrains.plugins.yaml")
    systemProperty("gradle.user.home", gradle.gradleUserHomeDir.absolutePath)
    // A local IDE may have custom startup agents. Keep the test JVM independent
    // of those machine-specific additions without changing the IDE installation.
    doFirst {
        val platformArgs = jvmArgumentProviders.flatMap { it.asArguments() }
        jvmArgumentProviders.clear()
        jvmArgs(platformArgs.filterNot { it.startsWith("-javaagent:") })
    }
}
intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "262.10968.63"; untilBuild = "262.*" }
    }
}
