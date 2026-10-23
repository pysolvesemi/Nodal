module { hw.module @FixedConstant(out word : i8) {
 %a = hw.constant 5 : i3
 %b = hw.constant 17 : i5
 %word = comb.concat %a, %b : i3, i5
 hw.output %word : i8
}}