module {
  hw.module @Concat<W: i32 = 1>(in %input : !hw.int<#hw.param.decl.ref<"W">>, out slot : i8) {
    %padding = sv.verbatim.expr "'0"() : () -> !hw.int<#hw.param.expr.add<#hw.param.expr.mul<#hw.param.decl.ref<"W">, -1>, 9>>
    %wide = "comb.concat"(%padding, %input) : (!hw.int<#hw.param.expr.add<#hw.param.expr.mul<#hw.param.decl.ref<"W">, -1>, 9>>, !hw.int<#hw.param.decl.ref<"W">>) -> i9
    %one = hw.constant 1 : i1
    %nested = comb.concat %one, %wide : i1, i9
    %slot = comb.extract %nested from 0 : (i10) -> i8
    hw.output %slot : i8
  }
}
