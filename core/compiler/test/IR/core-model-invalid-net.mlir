module {
  "nodal.module"() <{metadata = {}, sym_name = "BadNet"}> ({
    %net = "nodal.resolved_net"() <{
      metadata = {},
      name = "irq"
    }> : () -> !nodal.resolved<"open_drain", !nodal.bits<1>>
    %driver = "nodal.net_driver"(%net) <{
      driver_id = "BadNet.driver",
      metadata = {}
    }> : (!nodal.resolved<"open_drain", !nodal.bits<1>>) -> !nodal.driver<!nodal.bits<1>>
    %value = "nodal.constant"() <{
      metadata = {},
      value = 3 : i64
    }> : () -> i64
    %enable = "nodal.constant"() <{
      metadata = {},
      value = true
    }> : () -> i1
    "nodal.net_drive"(%net, %driver, %value, %enable) <{
      metadata = {},
      mode = "open_drain"
    }> : (!nodal.resolved<"open_drain", !nodal.bits<1>>, !nodal.driver<!nodal.bits<1>>, i64, i1) -> ()
  }) : () -> ()
}
