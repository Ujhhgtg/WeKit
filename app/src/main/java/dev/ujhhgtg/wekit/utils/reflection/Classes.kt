package dev.ujhhgtg.wekit.utils.reflection

inline val int: Class<Int> get() = Integer.TYPE

inline val bool: Class<Boolean> get() = java.lang.Boolean.TYPE

inline val byte: Class<Byte> get() = java.lang.Byte.TYPE

inline val short: Class<Short> get() = java.lang.Short.TYPE

inline val long: Class<Long> get() = java.lang.Long.TYPE

inline val float: Class<Float> get() = java.lang.Float.TYPE

inline val double: Class<Double> get() = java.lang.Double.TYPE

inline val char: Class<Char> get() = Character.TYPE

inline val void: Class<Void> get() = Void.TYPE

inline val BInt get() = Int::class.java

inline val BBool get() = Boolean::class.java

inline val BByte get() = Byte::class.java

inline val BShort get() = Short::class.java

inline val BLong get() = Long::class.java

inline val BFloat get() = Float::class.java

inline val BDouble get() = Double::class.java

inline val BChar get() = Char::class.java

inline val BString get() = String::class.java

inline val StrArr get() = Array<String>::class.java

inline val ObjArr get() = Array<Any>::class.java

inline val any get() = Any::class.java
