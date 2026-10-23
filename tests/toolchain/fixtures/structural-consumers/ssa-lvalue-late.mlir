module {
  hw.module @LeafOne(in %data: i8, out value: i8) { hw.output %data : i8 }
  hw.module @LValue(in %data: i8, out output: i16) {
    %bus = sv.wire : !hw.inout<i16>
    %read = sv.read_inout %bus : !hw.inout<i16>
    sv.generate "family" : {
      sv.generate.for %i : i32 = 0 : i32 to 2 : i32 step 1 : i32 name "lane" {
        %x = hw.instance "one" @LeafOne(data: %data: i8) -> (value: i8)
        %slice = sv.verbatim.expr "{{0}}[{{1}} * 8 +: 8]"(%bus, %i) : (!hw.inout<i16>, i32) -> !hw.inout<i8>
        sv.assign %slice, %x : i8
      }
    }
    hw.output %read : i16
  }
}
