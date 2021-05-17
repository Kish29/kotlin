// SKIP_KT_DUMP

// FILE: a.kt
package a

abstract class Base {
    protected fun method() = "OK"
}

// FILE: b.kt
import a.Base

class SubClass : Base() {
    fun testRef() = ::method

    fun testCall() = method()
}

