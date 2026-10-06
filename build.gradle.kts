plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    // Must track the Kotlin version exactly: it is a compiler plugin, not a library.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
