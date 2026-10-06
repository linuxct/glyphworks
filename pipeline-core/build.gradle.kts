plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
    `maven-publish`
}
kotlin { jvmToolchain(17) }
group = "space.linuxct.pipeline"
version = "1.0.0"
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
java { withSourcesJar() }
publishing {
    publications { create<MavenPublication>("core") {
        from(components["java"])
        groupId = project.group.toString(); artifactId = "pipeline-core"; version = project.version.toString()
        pom {
            name = "GlyphWorks Pipeline Core"
            description = "Portable block model, validation and deterministic execution engine."
            licenses { license { name = "GNU Affero General Public License, version 3"; url = "https://www.gnu.org/licenses/agpl-3.0.html" } }
        }
    } }
}

publishing { repositories { maven { name = "localSdk"; url = rootProject.layout.buildDirectory.dir("pipeline-sdk/maven").get().asFile.toURI() } } }
