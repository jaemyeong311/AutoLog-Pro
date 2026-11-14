pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") // Pleos Vehicle SDK (공식 샘플 앱과 동일)
    }
}
rootProject.name = "AutoLogPro_Vehicle_v1.6.13"
include(":app")
