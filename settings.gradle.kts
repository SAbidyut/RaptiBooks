pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "RaptiBooks"

include(":app")
include(":core:model")
include(":core:structure")
include(":core:reflow")
include(":core:pdf")
include(":core:ocr")
include(":core:database")
include(":core:data")
include(":core:ui")
include(":feature:library")
include(":feature:reader")
