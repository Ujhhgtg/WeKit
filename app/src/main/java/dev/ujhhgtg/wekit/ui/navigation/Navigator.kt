package dev.ujhhgtg.wekit.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ujhhgtg.wekit.utils.WeLogger
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey

// kang from KernelSU manager, with the anti-reenter pop fix from ReSukiSU manager
class Navigator(
    val backStack: NavBackStack
) {
    fun push(key: NavKey) {
        if (backStack.lastOrNull() == key) {
            WeLogger.i("Navigator", "Trying push current page to backStack again, ignore!")
            return
        }
        backStack.add(key)
    }

    fun replace(key: NavKey) {
        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = key
        } else {
            backStack.add(key)
        }
    }

    fun replaceAll(keys: List<NavKey>) {
        if (keys.isEmpty()) return
        if (backStack.isNotEmpty()) {
            backStack.clear()
            backStack.addAll(keys)
        }
    }

    private var lastPopTime = 0L

    fun pop() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPopTime < 100) {
            WeLogger.i("Navigator", "pop call more than 1 times in 100ms, ignore!")
            return
        }
        if (backStackSize() <= 1) return
        lastPopTime = System.currentTimeMillis()
        backStack.removeLastOrNull()
    }

    fun popUntil(predicate: (NavKey) -> Boolean) {
        while (backStack.isNotEmpty() && !predicate(backStack.last())) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun current(): NavKey? = backStack.lastOrNull()

    fun backStackSize(): Int = backStack.size
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}
