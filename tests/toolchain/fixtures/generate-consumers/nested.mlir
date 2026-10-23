module {
  hw.module @LeafOne(in %data: i8, out value: i8) { hw.output %data : i8 }
  hw.module @Middle(in %data: i8, out output: i16) {
    %bus = sv.wire : !hw.inout<i16>
    %read = sv.read_inout %bus : !hw.inout<i16>
    sv.generate "family" : {
      sv.generate.for %i : i32 = 0 : i32 to 2 : i32 step 1 : i32 name "lane" {
        %slice = sv.verbatim.expr "{{0}}[{{1}} * 8 +: 8]"(%bus, %i) : (!hw.inout<i16>, i32) -> !hw.inout<i8>
        %x = hw.instance "one" @LeafOne(data: %data: i8) -> (value: i8)
        sv.assign %slice, %x : i8
      }
    }
    hw.output %read : i16
  }
hw.module @Nested(in %data:i8,out value:i32) {
 %bus = sv.wire : !hw.inout<i32>
 %read = sv.read_inout %bus : !hw.inout<i32>
 sv.generate "rows" : {
  sv.generate.for %i : i32 = 0 : i32 to 2 : i32 step 1 : i32 name "row" {
   %slice = sv.verbatim.expr "{{0}}[{{1}} * 16 +: 16]"(%bus,%i) : (!hw.inout<i32>,i32) -> !hw.inout<i16>
   %x = hw.instance "middle" @Middle(data:%data:i8)->(output:i16)
   sv.assign %slice,%x:i16
  }
 }
 hw.output %read:i32
}}