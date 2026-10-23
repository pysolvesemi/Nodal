module {
  hw.module @LeafOne(in %data: i8, out value: i8) {
    hw.output %data : i8
  }
  hw.module @Graph(in %data: i8, out value: i8) {
    %bus = sv.wire : !hw.inout<i8>
    %read = sv.read_inout %bus : !hw.inout<i8>
    sv.assign %bus, %result : i8
    %result = hw.instance "child" @LeafOne(data: %data: i8) -> (value: i8)
    hw.output %read : i8
  }
}
