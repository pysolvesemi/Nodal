module {
  hw.module @Concat<W: i32 = 1>(in %input : !hw.int<#hw.param.decl.ref<"W">>, out slot : i8) {
    %padding = sv.verbatim.expr "'0"() : () -> !hw.int<#hw.param.expr.add<#hw.param.expr.mul<#hw.param.decl.ref<"W">, -1>, 9>>
    %wide = "comb.concat"(%padding, %input) : (!hw.int<#hw.param.expr.add<#hw.param.expr.mul<#hw.param.decl.ref<"W">, -1>, 9>>, !hw.int<#hw.param.decl.ref<"W">>) -> i9
    %mask = hw.constant 511 : i9
    %logical = comb.xor %wide, %mask : i9
    %slot = comb.extract %logical from 0 : (i9) -> i8
    hw.output %slot : i8
  }
}
