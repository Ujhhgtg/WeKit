package dev.ujhhgtg.wekit.features.items.scripting_java

import bsh.BshHook
import bsh.Interpreter
import bsh.LocalMethodHookParam
import bsh.Primitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScriptsDrmBypassHookTest {
    @Test
    fun onlyRegisteredInterpreterIsIntercepted() {
        val registered = Interpreter()
        val other = Interpreter()
        val hook = ScriptsDrmBypassHook()
        hook.registerInterpreter(registered)

        val registeredParam = param(registered, "checkAuthorization", Boolean::class.javaPrimitiveType)
        val otherParam = param(other, "checkAuthorization", Boolean::class.javaPrimitiveType)
        hook.beforeLocalMethod(registeredParam)
        hook.beforeLocalMethod(otherParam)

        assertTrue(registeredParam.isIntercepted)
        assertEquals(true, registeredParam.returnValue)
        assertFalse(otherParam.isIntercepted)
    }

    @Test
    fun registeredInterpreterAcceptsBooleanVoidObjectAndCollectionReturns() {
        val interpreter = Interpreter()
        val hook = ScriptsDrmBypassHook()
        hook.registerInterpreter(interpreter)

        val booleanParam = param(interpreter, "isUsingVPN", Boolean::class.javaPrimitiveType)
        val voidParam = param(interpreter, "showBlackToast", Void.TYPE)
        val objectParam = param(interpreter, "detectPacketCapture", Any::class.java)
        val collectionParam = param(interpreter, "getBlackFriends", List::class.java)

        listOf(booleanParam, voidParam, objectParam, collectionParam)
            .forEach(hook::beforeLocalMethod)

        assertTrue(booleanParam.isIntercepted)
        assertEquals(false, booleanParam.returnValue)
        assertTrue(voidParam.isIntercepted)
        assertEquals(Primitive.VOID, voidParam.returnValue)
        assertTrue(objectParam.isIntercepted)
        assertEquals(false, objectParam.returnValue)
        assertTrue(collectionParam.isIntercepted)
        assertTrue(collectionParam.returnValue is ArrayList<*>)
    }

    @Test
    fun incompatibleReturnTypesRemainUntouched() {
        val interpreter = Interpreter()
        val hook = ScriptsDrmBypassHook()
        hook.registerInterpreter(interpreter)

        val authorization = param(interpreter, "checkAuthorization", String::class.java)
        val friends = param(interpreter, "getBlackFriends", String::class.java)

        hook.beforeLocalMethod(authorization)
        hook.beforeLocalMethod(friends)

        assertFalse(authorization.isIntercepted)
        assertFalse(friends.isIntercepted)
    }

    @Test
    fun registeredInterpreterScopesEvalChildInterpreter() {
        val interpreter = Interpreter()
        val hook = ScriptsDrmBypassHook()
        hook.registerInterpreter(interpreter)

        val childParam = LocalMethodHookParam(
            Interpreter(interpreter),
            interpreter.nameSpace,
            "checkAuthorization",
            emptyArray(),
            emptyArray(),
            Boolean::class.javaPrimitiveType,
            null,
            false,
            emptyArray()
        )
        hook.beforeLocalMethod(childParam)

        assertTrue(childParam.isIntercepted)
        assertEquals(true, childParam.returnValue)
    }
    @Test
    fun registeredInterpreterScopesRealNestedEvalCalls() {
        val interpreter = Interpreter()
        val hook = ScriptsDrmBypassHook()
        hook.registerInterpreter(interpreter)
        var intercepted = false
        val recordingHook = object : BshHook {
            override fun beforeLocalMethod(param: LocalMethodHookParam) {
                hook.beforeLocalMethod(param)
                if (param.methodName == "checkAuthorization")
                    intercepted = param.isIntercepted
            }
        }
        Interpreter.bshHookManager.addHook(recordingHook)
        try {
            interpreter.eval("boolean nested() { return checkAuthorization(); } boolean checkAuthorization() { return false; } nested();")
        } finally {
            Interpreter.bshHookManager.removeHook(recordingHook)
        }
        assertTrue(intercepted)
    }

    @Test
    fun registerAndUnregisterAreIdempotent() {
        val interpreter = Interpreter()
        val hook = ScriptsDrmBypassHook()
        hook.registerInterpreter(interpreter)
        hook.registerInterpreter(interpreter)

        val registered = param(interpreter, "checkAuthorization", Boolean::class.javaPrimitiveType)
        hook.beforeLocalMethod(registered)
        assertTrue(registered.isIntercepted)

        hook.unregisterInterpreter(interpreter)
        hook.unregisterInterpreter(interpreter)
        val unregistered = param(interpreter, "checkAuthorization", Boolean::class.javaPrimitiveType)
        hook.beforeLocalMethod(unregistered)
        assertFalse(unregistered.isIntercepted)
    }

    private fun param(interpreter: Interpreter, methodName: String, returnType: Class<*>?) =
        LocalMethodHookParam(
            interpreter,
            interpreter.nameSpace,
            methodName,
            emptyArray(),
            emptyArray(),
            returnType,
            null,
            false,
            emptyArray()
        )
}
