root = 'MusicaIA'

pluginManagement {
    repositories {
        gradlePluginManagement {
            repositories {
                mavenCentral()
                google()
            }
        }
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include ':app'