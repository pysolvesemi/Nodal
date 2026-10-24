module { hw.module @Symbolic<W: i32 = 3>(in %a : !hw.int<#hw.param.decl.ref<"W">>, in %b : i1, out word : !hw.int<#hw.param.expr.add<#hw.param.decl.ref<"W">, 1>>) {
 %word = comb.concat %a, %b : !hw.int<#hw.param.decl.ref<"W">>, i1
 hw.output %word : !hw.int<#hw.param.expr.add<#hw.param.decl.ref<"W">, 1>>
}}