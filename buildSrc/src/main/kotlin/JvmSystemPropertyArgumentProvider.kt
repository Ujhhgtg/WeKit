import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider

abstract class JvmSystemPropertyArgumentProvider : CommandLineArgumentProvider {
    @get:Input
    abstract val properties: MapProperty<String, String>

    override fun asArguments(): Iterable<String> =
        properties.get().map { (name, value) -> "-D$name=$value" }
}
