package dev.ujhhgtg.wekit.features.items.scripting_python.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PythonPluginScopeTest {
    @Test
    fun `close is LIFO idempotent and continues after cleanup failure`() {
        val scope = PythonPluginScope("dev.example.test")
        val calls = mutableListOf<Int>()
        scope.defer { calls += 1 }
        scope.defer {
            calls += 2
            error("cleanup failed")
        }
        scope.defer { calls += 3 }

        val errors = scope.close()

        assertEquals(listOf(3, 2, 1), calls)
        assertEquals(1, errors.size)
        assertEquals(emptyList<Throwable>(), scope.close())
        assertThrows(IllegalStateException::class.java) { scope.defer {} }
    }
}
