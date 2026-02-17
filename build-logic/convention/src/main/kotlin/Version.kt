import org.gradle.api.JavaVersion
import org.gradle.api.Project
import java.io.File
import java.util.Properties

object Version {
    val java = JavaVersion.VERSION_17

    const val compileSdkVersion = 36
    val buildToolsVersion = findBuildToolsVersion()
    const val minSdk = 35
    const val targetSdk = 35

    private const val defaultNdkVersion = "27.0.12077973"
    private const val defaultCMakeVersion = "3.28.0+"

    fun getNdkVersion(): String {
        return defaultNdkVersion
    }

    fun getCMakeVersion(): String {
        return defaultCMakeVersion
    }

    fun getLocalProperty(project: Project, propertyName: String): String? {
        val rootProject = project.rootProject
        val localProp = File(rootProject.projectDir, "local.properties")
        if (!localProp.exists()) {
            return null
        }
        val localProperties = Properties()
        localProp.inputStream().use {
            localProperties.load(it)
        }
        return localProperties.getProperty(propertyName, null)
    }

    private fun getEnvVariable(name: String): String? {
        return System.getenv(name)
    }

    private fun findBuildToolsVersion(): String {
        val defaultBuildToolsVersion = "35.0.0"
        return File(System.getenv("ANDROID_HOME"), "build-tools").listFiles()?.filter { it.isDirectory }?.maxOfOrNull { it.name }
            ?.also { println("Using build tools version $it") }
            ?: defaultBuildToolsVersion
    }
}
