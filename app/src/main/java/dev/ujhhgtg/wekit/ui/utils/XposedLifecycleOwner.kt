package dev.ujhhgtg.wekit.ui.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

// used for injected 'ComposeView's
class XposedLifecycleOwner private constructor() :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle
        field = LifecycleRegistry(this)
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)

    fun onResume() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

    fun onPause() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)

    fun onStop() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

    fun onDestroy() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    fun moveTo(state: Lifecycle.State) {
        lifecycle.currentState = state
        if (state == Lifecycle.State.DESTROYED) viewModelStore.clear()
    }

    companion object {
        fun create(initialState: Lifecycle.State = Lifecycle.State.RESUMED): XposedLifecycleOwner {
            return XposedLifecycleOwner().apply {
                onCreate()
                moveTo(initialState)
            }
        }
    }
}
