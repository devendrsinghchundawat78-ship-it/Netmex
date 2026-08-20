// Keep the Gradle project identity stable because Compose generates the
// `nuvio.composeapp.generated.resources` package from it. User-facing branding is Netmex.
rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // Cloudstream publishes its binary-compatible extension API through JitPack.
        // This is only packaged by Nuvio's Android "full" distribution.
        maven("https://jitpack.io")
    }
}

include(":composeApp")
include(":androidApp")
