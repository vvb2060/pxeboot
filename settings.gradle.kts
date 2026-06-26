pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        val agp = "9.2.1"
        id("com.android.application") version agp
        id("com.android.library") version agp
        id("com.android.settings") version agp
        id("com.android.lint") version agp
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("com.android.settings")
}

android {
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    minSdk {
        version = release(26)
    }
    targetSdk {
        version = release(37)
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")

rootProject.name = "pxeBoot"
include("app")
