package dev.ujhhgtg.wekit.features.api.core

import android.annotation.SuppressLint
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import java.lang.reflect.Method

@Feature(
    id = "Unsafe 服务",
    nameRes = "feature_we_unsafe_api_name",
    categoryIds = [FeatureCategoryIds.API],
    descriptionRes = "feature_we_unsafe_api_description",
)
object WeUnsafeApi : ApiFeature() {

    private lateinit var theUnsafe: Any
    private lateinit var mAllocateInstance: Method

    @SuppressLint("DiscouragedPrivateApi")
    override fun onEnable() {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
        theUnsafe = theUnsafeField.makeAccessible().get(null)!!
        mAllocateInstance = unsafeClass.getMethod(
            "allocateInstance",
            Class::class.java
        )
    }

    fun allocateInstance(clazz: Class<*>): Any? = mAllocateInstance.invoke(theUnsafe, clazz)
}
