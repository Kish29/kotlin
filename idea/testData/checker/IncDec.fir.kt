class IncDec() {
  operator fun inc() : IncDec = this
  operator fun dec() : IncDec = this
}

fun testIncDec() {
  var x = IncDec()
  x++
  ++x
  x--
  --x
  x = x++
  x = x--
  x = ++x
  x = --x
}

class WrongIncDec() {
  operator fun inc() : Int = 1
  operator fun dec() : Int = 1
}

fun testWrongIncDec() {
  var x = WrongIncDec()
  <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Int but WrongIncDec was expected">x++</error>
  <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Int but WrongIncDec was expected">++x</error>
  <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Int but WrongIncDec was expected">x--</error>
  <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Int but WrongIncDec was expected">--x</error>
}

class UnitIncDec() {
  operator fun inc() : Unit {}
  operator fun dec() : Unit {}
}

fun testUnitIncDec() {
  var x = UnitIncDec()
  <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Unit but UnitIncDec was expected">x++</error>
  <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Unit but UnitIncDec was expected">++x</error>
  <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Unit but UnitIncDec was expected">x--</error>
  <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Unit but UnitIncDec was expected">--x</error>
  x = <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Unit but UnitIncDec was expected">x++</error>
  x = <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Unit but UnitIncDec was expected">x--</error>
  x = <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Unit but UnitIncDec was expected">++x</error>
  x = <error descr="[TYPE_MISMATCH] Type mismatch: inferred type is kotlin/Unit but UnitIncDec was expected">--x</error>
}
