module {
  hw.module @Leaf(in %data: i8, out one: i8, out two: i8) {
    hw.output %data, %data : i8, i8
  }
  hw.module @Top<ENABLE: i32 = 1>(in %data: i8, out one: i8, out two: i8) {
    %a = sv.wire : !hw.inout<i8>
    %b = sv.wire : !hw.inout<i8>
    %ar = sv.read_inout %a : !hw.inout<i8>
    %br = sv.read_inout %b : !hw.inout<i8>
    sv.generate "choice" : {
      sv.generate.case #hw.param.decl.ref<"ENABLE"> : i32 [
      case (1 : i32, "matched") {
        %x, %y = hw.instance "scope" @Leaf(data: %data: i8) -> (one: i8, two: i8)
        sv.assign %a, %x : i8
        sv.assign %b, %y : i8
      }
      case (unit, "other") {
        sv.assign %a, %data : i8
        sv.assign %b, %data : i8
      }
      ]
    }
    hw.output %ar, %br : i8, i8
  }
}
