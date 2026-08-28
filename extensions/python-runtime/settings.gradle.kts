pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://chaquo.com/maven")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { excludeGroup("dev.ujhhgtg.wekit") } }
        mavenCentral { content { excludeGroup("dev.ujhhgtg.wekit") } }
        val apiRepository = providers.gradleProperty("wekitPythonApiRepo")
            .orElse(System.getenv("WEKIT_PYTHON_API_REPO") ?: "")
        if (apiRepository.isPresent && apiRepository.get().isNotBlank()) {
            maven {
                name = "WeKitPythonApi"
                url = uri(apiRepository.get())
                content { includeGroup("dev.ujhhgtg.wekit") }
            }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "wekit-python-runtime"
include(":runtime")
