package dev.ujhhgtg.wekit.utils.reflection

inline val int: Class<Int> get() = Int::class.javaPrimitiveType!!

inline val bool: Class<Boolean> get() = Boolean::class.javaPrimitiveType!!

inline val byte: Class<Byte> get() = Byte::class.javaPrimitiveType!!

inline val short: Class<Short> get() = Short::class.javaPrimitiveType!!

inline val long: Class<Long> get() = Long::class.javaPrimitiveType!!

inline val float: Class<Float> get() = Float::class.javaPrimitiveType!!

inline val double: Class<Double> get() = Double::class.javaPrimitiveType!!

inline val char: Class<Char> get() = Char::class.javaPrimitiveType!!

inline val void: Class<Void> get() = Void.TYPE

inline val BInt get() = Int::class.javaObjectType

inline val BBool get() = Boolean::class.javaObjectType

inline val BByte get() = Byte::class.javaObjectType

inline val BShort get() = Short::class.javaObjectType

inline val BLong get() = Long::class.javaObjectType

inline val BFloat get() = Float::class.javaObjectType

inline val BDouble get() = Double::class.javaObjectType

inline val BChar get() = Char::class.javaObjectType

inline val BString get() = String::class.java

inline val StrArr get() = Array<String>::class.java

inline val ObjArr get() = Array<Any>::class.java

inline val any get() = Any::class.java

fun Class<*>.boxed(): Class<*> = when (this) {
    byte -> BByte
    short -> BShort
    int -> BInt
    long -> BLong
    float -> BFloat
    double -> BDouble
    bool -> BBool
    char -> BChar
    else -> this
}
