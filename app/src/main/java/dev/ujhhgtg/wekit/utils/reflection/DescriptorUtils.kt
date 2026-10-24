package dev.ujhhgtg.wekit.utils.reflection

import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

fun String.descriptorToMethod(classLoader: ClassLoader): Method {
    val arrow = indexOf("->")
    require(arrow > 0) { "Invalid method descriptor: $this" }

    val descriptorStart = indexOf('(', arrow + 2)
    require(descriptorStart > arrow + 2) {
        "Invalid method descriptor: $this"
    }

    val ownerDescriptor = substring(0, arrow)
    require(ownerDescriptor.startsWith("L") && ownerDescriptor.endsWith(";")) {
        "Invalid owner descriptor: $ownerDescriptor"
    }

    val owner =
        Class.forName(
            ownerDescriptor.substring(1, ownerDescriptor.length - 1).replace('/', '.'),
            false,
            classLoader,
        )

    val methodName = substring(arrow + 2, descriptorStart)
    val methodType =
        MethodType.fromMethodDescriptorString(
            substring(descriptorStart),
            classLoader,
        )

    return owner.declaredMethods.firstOrNull {
        it.name == methodName &&
            it.returnType == methodType.returnType() &&
            it.parameterTypes.contentEquals(methodType.parameterArray())
    } ?: throw NoSuchMethodException(this)
}

fun String.descriptorToConstructor(classLoader: ClassLoader): Constructor<*> {
    val arrow = indexOf("->")
    require(arrow > 0) { "Invalid constructor descriptor: $this" }

    val descriptorStart = indexOf('(', arrow + 2)
    require(descriptorStart > arrow + 2) {
        "Invalid constructor descriptor: $this"
    }

    val ownerDescriptor = substring(0, arrow)
    require(ownerDescriptor.startsWith("L") && ownerDescriptor.endsWith(";")) {
        "Invalid owner descriptor: $ownerDescriptor"
    }

    val owner =
        Class.forName(
            ownerDescriptor.substring(1, ownerDescriptor.length - 1).replace('/', '.'),
            false,
            classLoader,
        )

    require(substring(arrow + 2, descriptorStart) == "<init>") {
        "Not a constructor descriptor: $this"
    }

    val type =
        MethodType.fromMethodDescriptorString(
            substring(descriptorStart),
            classLoader,
        )

    require(type.returnType() == Void.TYPE) {
        "Constructor descriptor must return void: $this"
    }

    return owner.getDeclaredConstructor(*type.parameterArray())
}

fun String.descriptorToExecutable(classLoader: ClassLoader): Executable {
    val arrow = indexOf("->")
    require(arrow > 0) { "Invalid descriptor: $this" }

    val descriptorStart = indexOf('(', arrow + 2)
    require(descriptorStart > arrow + 2) {
        "Invalid descriptor: $this"
    }

    val ownerDescriptor = substring(0, arrow)
    require(ownerDescriptor.startsWith("L") && ownerDescriptor.endsWith(";")) {
        "Invalid owner descriptor: $ownerDescriptor"
    }

    val owner =
        Class.forName(
            ownerDescriptor.substring(1, ownerDescriptor.length - 1).replace('/', '.'),
            false,
            classLoader,
        )

    val name = substring(arrow + 2, descriptorStart)
    val type =
        MethodType.fromMethodDescriptorString(
            substring(descriptorStart),
            classLoader,
        )

    return when (name) {
        "<init>" -> {
            require(type.returnType() == Void.TYPE) {
                "Constructor must return void: $this"
            }
            owner.getDeclaredConstructor(*type.parameterArray())
        }

        "<clinit>" -> {
            throw IllegalArgumentException(
                "Class initializer has no reflective Executable: $this",
            )
        }

        else -> {
            owner.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.returnType == type.returnType() &&
                    method.parameterTypes.contentEquals(type.parameterArray())
            } ?: throw NoSuchMethodException(this)
        }
    }
}

fun String.descriptorSplitParameterTypes(): List<String> = buildList {
    val s = this@descriptorSplitParameterTypes
    var i = 0
    while (i < s.length) {
        val start = i
        while (i < s.length && s[i] == '[') i++

        if (s.getOrNull(i) == 'L') {
            i =
                s.indexOf(';', i).also {
                    require(it >= 0) { "Invalid descriptor: $s" }
                } + 1
        } else {
            require(i < s.length) { "Invalid descriptor: $s" }
            i++
        }

        add(s.substring(start, i))
    }
}
