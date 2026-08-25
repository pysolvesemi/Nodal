// DEFAULT: nodal.pipeline.normalized = "v1"
// DEFAULT: nodal.pipeline.profile = "default"
// RELEASE: nodal.pipeline.profile = "release"
// FAST: nodal.pipeline.profile = "fast"

module attributes {
  nodal.target.profile = "mixed_signal",
  nodal.verify.analog_topology = true,
  nodal.verify.assignment_coverage = true,
  nodal.verify.cdc_rdc_safe = true,
  nodal.verify.clock_reset_domains = true,
  nodal.verify.combinational_acyclic = true,
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = true,
  nodal.verify.enum_fsm = true,
  nodal.verify.hierarchy_closed = true,
  nodal.verify.latch_free = true,
  nodal.verify.layout_storage = true,
  nodal.verify.memory_effects = true,
  nodal.verify.mixed_signal_bridges = true,
  nodal.verify.parameters_complete = true,
  nodal.verify.protocol_pipeline = true,
  nodal.verify.target_capability = true,
  nodal.verify.width_sign_shape = true
} {
  "nodal.interface"() <{
    metadata = {abi = "logical"},
    sym_name = "Link"
  }> ({
    "nodal.interface_role"() <{
      kind = "producer",
      metadata = {},
      sym_name = "source"
    }> : () -> ()
    "nodal.interface_role"() <{
      kind = "consumer",
      metadata = {},
      sym_name = "sink"
    }> : () -> ()
    "nodal.interface_member"() <{
      metadata = {},
      protocol = "plain",
      roles = ["source", "sink"],
      sym_name = "data",
      type = !nodal.uint<8>
    }> : () -> ()
  }) : () -> ()

  "nodal.enum"() <{
    encoding = "sequential",
    metadata = {abi = "canonical"},
    sym_name = "State",
    underlying_type = !nodal.uint<1>
  }> ({
    "nodal.enum_case"() <{
      metadata = {},
      sym_name = "Idle",
      value = 0 : i64
    }> : () -> ()
    "nodal.enum_case"() <{
      metadata = {},
      sym_name = "Run",
      value = 1 : i64
    }> : () -> ()
  }) : () -> ()

  "nodal.module"() <{
    metadata = {reusable = true},
    sym_name = "Child"
  }> ({
    "nodal.domain_requirement"() <{
      metadata = {},
      sym_name = "core"
    }> : () -> ()
    "nodal.parameter"() <{
      default_value = 8 : i64,
      metadata = {target_visible = true},
      sym_name = "WIDTH",
      type = i64,
      variability = "symbolic"
    }> : () -> ()
    "nodal.port"() <{
      direction = "input",
      domain = @core,
      metadata = {},
      sym_name = "input",
      type = !nodal.uint<8>
    }> : () -> ()
  }) : () -> ()

  "nodal.module"() <{
    metadata = {root = true},
    sym_name = "Top"
  }> ({
    "nodal.domain"() <{
      edge = "rising",
      metadata = {},
      reset_policy = "async_assert_sync_release",
      sym_name = "core"
    }> : () -> ()
    "nodal.domain"() <{
      edge = "rising",
      metadata = {},
      reset_policy = "sync",
      sym_name = "async"
    }> : () -> ()
    "nodal.parameter"() <{
      default_value = 2 : i64,
      metadata = {target_visible = true},
      sym_name = "SIZE",
      type = i64,
      variability = "symbolic"
    }> : () -> ()
    "nodal.port"() <{
      direction = "input",
      domain = @core,
      metadata = {},
      sym_name = "enable",
      type = !nodal.bits<1>
    }> : () -> ()
    "nodal.port"() <{
      direction = "output",
      domain = @core,
      metadata = {},
      sym_name = "data",
      type = !nodal.shaped<"SIZE", !nodal.uint<8>>
    }> : () -> ()
    "nodal.instance"() <{
      domain_bindings = {core = @core},
      metadata = {},
      module = @Child,
      parameter_bindings = {WIDTH = 8 : i64},
      sym_name = "child"
    }> : () -> ()
    "nodal.interface_instance"() <{
      definition = @Link,
      metadata = {},
      role = "source",
      sym_name = "link"
    }> : () -> ()
    %member = "nodal.member_access"() <{
      instance = @link,
      metadata = {},
      path = "data"
    }> : () -> !nodal.uint<8>
    "nodal.interface_abi"() <{
      layout_policy = "logical_only",
      logical_path = "Top.link",
      members = ["data"],
      metadata = {}
    }> : () -> ()

    %zero = "nodal.constant"() <{
      metadata = {},
      value = 0 : i64
    }> : () -> !nodal.uint<8>
    %shape = "nodal.shape_view"(%zero) <{
      dimensions = "SIZE",
      materialization = "explicit_view",
      metadata = {storage = "structural"},
      observability = "source_mapped",
      origin = "Top.data"
    }> : (!nodal.uint<8>) -> !nodal.shaped<"SIZE", !nodal.uint<8>>
    "nodal.generate"() <{
      induction = "g",
      lower = 0 : i64,
      metadata = {},
      step = 1 : i64,
      upper = 2 : i64
    }> ({
    ^bb0:
    }) : () -> ()
    "nodal.hardware_loop"() <{
      effect_policy = "ordered",
      induction = "i",
      lower = 0 : i64,
      metadata = {},
      step = 1 : i64,
      upper = 4 : i64
    }> ({
    ^bb0:
    }) : () -> ()

    %net = "nodal.resolved_net"() <{
      metadata = {},
      name = "irq"
    }> : () -> !nodal.resolved<"open_drain", !nodal.bits<1>>
    %driver = "nodal.net_driver"(%net) <{
      driver_id = "Top.irq.driver",
      metadata = {}
    }> : (!nodal.resolved<"open_drain", !nodal.bits<1>>) -> !nodal.driver<!nodal.bits<1>>
    %read = "nodal.net_read"(%net) <{
      metadata = {}
    }> : (!nodal.resolved<"open_drain", !nodal.bits<1>>) -> !nodal.bits<1>
    %drive_enable = "nodal.constant"() <{
      metadata = {},
      value = true
    }> : () -> i1
    "nodal.net_drive"(%net, %driver, %read, %drive_enable) <{
      metadata = {},
      mode = "open_drain"
    }> : (!nodal.resolved<"open_drain", !nodal.bits<1>>, !nodal.driver<!nodal.bits<1>>, !nodal.bits<1>, i1) -> ()

    %positive = "nodal.terminal"() <{
      metadata = {},
      name = "vin"
    }> : () -> !nodal.terminal<"electrical">
    %negative = "nodal.node"() <{
      metadata = {},
      name = "gnd"
    }> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%positive, %negative) <{
      metadata = {}
    }> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    %voltage = "nodal.access"(%branch) <{
      kind = "potential",
      metadata = {}
    }> : (!nodal.branch<"electrical">) -> f64
    %sample = "nodal.bridge"(%voltage) <{
      destination_domain = "core",
      kind = "threshold_sample",
      metadata = {},
      source_domain = "analog"
    }> : (f64) -> i1
    %synced = "nodal.crossing"(%sample) <{
      destination_domain = @core,
      kind = "sync",
      metadata = {stages = 2 : i64},
      source_domain = @async
    }> : (i1) -> i1

    "nodal.fsm"() <{
      domain = @core,
      encoding = "compact",
      illegal_policy = "recover",
      metadata = {},
      state_type = !nodal.enum<"State", 1>,
      sym_name = "control"
    }> ({
      "nodal.fsm_state"() <{
        initial = true,
        metadata = {},
        sym_name = "Idle",
        terminal = false
      }> ({
        "nodal.fsm_transition"() <{
          condition = "enable",
          destination = @Run,
          metadata = {},
          priority = 0 : i64
        }> : () -> ()
      }) : () -> ()
      "nodal.fsm_state"() <{
        initial = false,
        metadata = {},
        sym_name = "Run",
        terminal = true
      }> ({
        "nodal.fsm_action"() <{
          effect = "run",
          metadata = {},
          phase = "active"
        }> : () -> ()
      }) : () -> ()
      "nodal.fsm_completion"() <{
        destination = @Idle,
        kind = "restart",
        metadata = {},
        source = @Run
      }> : () -> ()
    }) : () -> ()
    "nodal.state_owner"() <{
      domain = @core,
      metadata = {},
      state = @control::@Run
    }> : () -> ()
    "nodal.timing_provenance"() <{
      metadata = {},
      owner = @control,
      relationship = "single_domain"
    }> : () -> ()
  }) : () -> ()
}
