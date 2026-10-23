import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.ideamcp"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde) else intellijIdeaUltimate("2025.2")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test {
    systemProperty("idea.mcp.test.jdk", javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(21) }.get().metadata.installationPath.asFile.absolutePath)
    systemProperty("idea.load.plugins.id", "dev.ideamcp.symbols,com.intellij.java")
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
        ideaVersion { sinceBuild = "252"; untilBuild = "252.*" }
    }
}
