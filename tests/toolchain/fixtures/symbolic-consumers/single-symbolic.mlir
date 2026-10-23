module { hw.module @Single<W: i32 = 3>(in %a : !hw.int<#hw.param.decl.ref<"W">>, out word : !hw.int<#hw.param.decl.ref<"W">>) {
 %word = comb.concat %a : !hw.int<#hw.param.decl.ref<"W">>
 hw.output %word : !hw.int<#hw.param.decl.ref<"W">>
}}