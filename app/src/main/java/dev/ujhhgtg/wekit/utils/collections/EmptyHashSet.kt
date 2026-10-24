package dev.ujhhgtg.wekit.utils.collections

import androidx.annotation.Keep
import java.io.Serializable

/** Returns an empty read-only hash set. The returned list is serializable (JVM). */
@Suppress("UNCHECKED_CAST") fun <T> emptyHashSet(): HashSet<T> = EmptyHashSet as HashSet<T>

@Keep
private object EmptyHashSet : HashSet<Nothing>(), Serializable {
    @Suppress("unused") private const val serialVersionUID: Long = 3406603774120516569L

    override fun equals(other: Any?): Boolean = other is Set<*> && other.isEmpty()

    override fun hashCode(): Int = 0

    override fun toString(): String = "[]"

    override val size: Int
        get() = 0

    override fun isEmpty(): Boolean = true

    override fun contains(element: Nothing): Boolean = false

    override fun containsAll(elements: Collection<Nothing>): Boolean = elements.isEmpty()

    override fun iterator(): MutableIterator<Nothing> = EmptyMutableIterator

    override fun add(element: Nothing): Boolean =
        throw UnsupportedOperationException("Operation is not supported for empty hash set.")

    override fun addAll(elements: Collection<Nothing>): Boolean =
        throw UnsupportedOperationException("Operation is not supported for empty hash set.")

    override fun remove(element: Nothing): Boolean =
        throw UnsupportedOperationException("Operation is not supported for empty hash set.")

    override fun removeAll(elements: Collection<Nothing>): Boolean =
        throw UnsupportedOperationException("Operation is not supported for empty hash set.")

    override fun retainAll(elements: Collection<Nothing>): Boolean =
        throw UnsupportedOperationException("Operation is not supported for empty hash set.")

    override fun clear(): Unit =
        throw UnsupportedOperationException("Operation is not supported for empty hash set.")

    @Suppress("unused") private fun readResolve(): Any = EmptyHashSet
}

@Keep
private object EmptyMutableIterator : MutableIterator<Nothing> {
    override fun hasNext(): Boolean = false

    override fun next(): Nothing = throw NoSuchElementException()

    override fun remove(): Unit = throw IllegalStateException()
}
