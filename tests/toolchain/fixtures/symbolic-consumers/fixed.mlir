module { hw.module @Fixed(in %a : i3, in %b : i5, out word : i8) {
 %word = comb.concat %a, %b : i3, i5
 hw.output %word : i8
}}