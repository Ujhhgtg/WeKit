package dev.ujhhgtg.wekit.features.items.scripting_java

import bsh.BshHook
import bsh.Interpreter
import bsh.LocalMethodHookParam
import bsh.Primitive
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

class ScriptsDrmBypassHook : BshHook {
    private val interpreters = ConcurrentHashMap.newKeySet<Interpreter>()

    fun registerInterpreter(interpreter: Interpreter) {
        interpreters += interpreter
    }

    fun unregisterInterpreter(interpreter: Interpreter) {
        interpreters -= interpreter
    }

    private fun isRegistered(interpreter: Interpreter?): Boolean {
        var current = interpreter
        while (current != null) {
            if (interpreters.contains(current)) return true
            current = current.getParent()
        }
        return false
    }

    override fun beforeLocalMethod(param: LocalMethodHookParam) {
        if (!isRegistered(param.interpreter)) return

        val returnType = param.returnType
        when (param.methodName) {
            "isUsingVPN", "isUsingProxy", "hasSuspiciousCertificates",
            "isSSLValidationBypassed", "detectPacketCapture", "showAntiCaptureDialog",
            "fetchBlackListFromNetwork", "checkBlackListSync", "showBlackToast" -> {
                if (returnType == Void.TYPE) {
                    param.returnValue = Primitive.VOID
                } else if (returnType == null || returnType == Any::class.java ||
                    returnType == Boolean::class.javaPrimitiveType ||
                    returnType == Boolean::class.java) {
                    param.returnValue = false
                } else {
                    return
                }
                param.isIntercepted = true
            }

            "getBlackFriends" -> {
                if (returnType != null && returnType != Any::class.java &&
                    !returnType.isAssignableFrom(ArrayList::class.java)) return
                param.returnValue = arrayListOf<Any>()
                param.isIntercepted = true
            }

            "checkAuthorization" -> {
                if (returnType == null || returnType == Any::class.java ||
                    returnType == Boolean::class.javaPrimitiveType ||
                    returnType == Boolean::class.java) {
                    param.returnValue = true
                    param.isIntercepted = true
                }
            }
        }
    }
}
