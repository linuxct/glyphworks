plugins { id("com.android.library"); `maven-publish` }
group = "space.linuxct.pipeline"
version = "1.0.0"
android {
    namespace = "space.linuxct.pipeline.android"
    compileSdk = 37
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    publishing { singleVariant("release") { withSourcesJar() } }
}
kotlin { jvmToolchain(17) }
dependencies { api(project(":pipeline-core")) }
afterEvaluate {
    publishing { publications { create<MavenPublication>("android") {
        from(components["release"])
        groupId = project.group.toString(); artifactId = "pipeline-android"; version = project.version.toString()
        pom {
            name = "GlyphWorks Pipeline Android"
            description = "Android lifecycle scheduling adapter for the Pipeline Core engine."
            licenses { license { name = "GNU Affero General Public License, version 3"; url = "https://www.gnu.org/licenses/agpl-3.0.html" } }
        }
    } } }
}

publishing { repositories { maven { name = "localSdk"; url = rootProject.layout.buildDirectory.dir("pipeline-sdk/maven").get().asFile.toURI() } } }
