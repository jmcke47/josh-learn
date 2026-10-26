buildscript {
    repositories { google(); mavenCentral() }
    // AGP's built-in Kotlin defaults to 2.2; LiteRT-LM is compiled with Kotlin 2.4, so use a 2.4 compiler.
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }
}
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
}
