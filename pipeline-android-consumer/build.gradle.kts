plugins { id("com.android.application") }
android {
    namespace = "space.linuxct.pipeline.consumer"
    compileSdk = 37
    defaultConfig { applicationId = "space.linuxct.pipeline.consumer"; minSdk = 26; targetSdk = 37 }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
kotlin { jvmToolchain(17) }
dependencies { implementation(if (providers.gradleProperty("pipelineSdkArtifacts").orNull == "true") "space.linuxct.pipeline:pipeline-android:1.0.0" else project(":pipeline-android")) }
