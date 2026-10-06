plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies { implementation(if (providers.gradleProperty("pipelineSdkArtifacts").orNull == "true") "space.linuxct.pipeline:pipeline-core:1.0.0" else project(":pipeline-core")); testImplementation("junit:junit:4.13.2") }
