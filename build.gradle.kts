plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.rameshnr"
version = "1.3.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compiled against the oldest supported IDE (2023.1), so only APIs every supported version has are used.
        // .\gradlew.bat runIdeLocal runs the plugin in the installed IDE instead (see below).
        intellijIdeaCommunity("2023.1.7")
        bundledPlugin("com.intellij.java")
        // Tests that read real Java code with the IDE's own parser (e.g. FlowCallPathTest)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }

    // Unit tests for the parts that don't need a running IDE (src/test): .\gradlew.bat test
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // the IDE's test framework is JUnit 4 based: run those tests next to the JUnit 5 ones
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

// IntelliJ IDEA 2023.1 - 2023.3 run on Java 17: the plugin is Java 17 bytecode and uses only Java 17 APIs.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.test {
    useJUnitPlatform()
}

// The version shown in Settings ⚙️ → Info (read by FlowInfoDialog, without the IDE's internal plugin API).
val writeVersion = tasks.register("writeVersion") {
    val out = layout.buildDirectory.dir("generated/version")
    val ver = project.version.toString()
    inputs.property("version", ver)
    outputs.dir(out)
    doLast { out.get().file("flowtracker/version.properties").asFile.apply { parentFile.mkdirs(); writeText("version=$ver\n") } }
}
sourceSets.main { resources.srcDir(writeVersion) }

// Web folder that holds the screenshots for the plugin description (gradle.properties -> pluginImagesUrl).
val pluginImagesUrl = providers.gradleProperty("pluginImagesUrl").orElse("")

intellijPlatform {
    pluginConfiguration {
        // The Overview text in Settings -> Plugins. Its images need a web address: with pluginImagesUrl set,
        // @IMAGES@ becomes that address; without it, the image blocks are left out.
        description = providers.fileContents(layout.projectDirectory.file("src/main/plugin-description.html")).asText
            .zip(pluginImagesUrl) { html, url ->
                val body = html.replaceFirst(Regex("(?s)^\\s*<!--.*?-->\\s*"), "")
                if (url.isBlank()) body.replace(Regex("(?s)<!--img-->.*?<!--/img-->\\s*"), "")
                else body.replace("@IMAGES@", url.trimEnd('/')).replace("<!--img-->", "").replace("<!--/img-->", "")
            }
        ideaVersion {
            sinceBuild = "231" // IntelliJ IDEA 2023.1
            untilBuild = provider { null }
        }
    }

    // .\gradlew.bat verifyPlugin: the same Plugin Verifier JetBrains Marketplace runs (deprecated / internal API usages).
    pluginVerification {
        // One IDE per year of supported versions, plus the newest (Community until 2025.2, then the unified IDE).
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2023.1.7")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2023.3.8")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.6")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7.2")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.4")
            local("C:/Users/RameshNR/AppData/Local/Programs/IntelliJ IDEA OSS")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "263.7031.23")
        }
    }

    // JetBrains Marketplace (see README → Publish to JetBrains Marketplace). Nothing is read until
    // signPlugin / publishPlugin run; the values come from environment variables, never from this file.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// .\gradlew.bat runIdeLocal: the plugin in the installed IntelliJ IDEA (runIde starts the 2023.1 it's compiled against).
intellijPlatformTesting {
    runIde {
        register("runIdeLocal") {
            localPath = file("C:/Users/RameshNR/AppData/Local/Programs/IntelliJ IDEA OSS")
            task {
                // test projects use "Gradle JVM: JAVA_HOME": without it their Gradle sync fails and their code
                // isn't set up (nothing resolves)
                val jdk = file(System.getProperty("user.home") + "/.jdks/temurin-25.0.4.1")
                if (System.getenv("JAVA_HOME").isNullOrBlank() && jdk.isDirectory) environment("JAVA_HOME", jdk.path)
            }
        }
        // The oldest IDE the Gradle plugin can start (2023.3; it can't start 2023.1, which verifyPlugin still checks).
        register("runIdeOldest") {
            type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity
            version = "2023.3.8"
        }
    }
}
