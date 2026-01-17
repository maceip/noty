buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

plugins {
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}
