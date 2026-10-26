pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "JoshLearn"
include(":app")
include(":oldschool-theme") // vendored Old School theme kit (shared with Josh Notes; re-sync with /workspace/shared/oldschool-theme/tools/sync-into.sh)
