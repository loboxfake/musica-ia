rootProject.name = "MusicaIA"


pluginManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "MavenCentralEuropeMirror"
                    url = uri("https://maven-central-eu.storage-download.googleapis.com/maven2/")
                }
            }
            filter {
                includeGroup("org.jetbrains.kotlin")
            }
        }
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        exclusiveContent {
            forRepository {
                maven {
                    name = "MavenCentralEuropeMirror"
                    url = uri("https://maven-central-eu.storage-download.googleapis.com/maven2/")
                }
            }
            filter {
                includeGroup("org.jetbrains.kotlin")
            }
        }
        mavenCentral()
    }
}

include(":app")
