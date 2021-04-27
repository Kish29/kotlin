class IncDec {
    operator fun inc(): Unit {}
}

fun foo(): IncDec {
    var x = IncDec()
    x = <!TYPE_MISMATCH!>x++<!>
    <!TYPE_MISMATCH!>x++<!>
    return x
}
