//===- NodalOps.td - Nodal operation definitions ----------*- tablegen -*-===//
//
// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
//
//===----------------------------------------------------------------------===//

#ifndef NODAL_DIALECT_NODAL_NODALOPS_TD
#define NODAL_DIALECT_NODAL_NODALOPS_TD

include "mlir/IR/BuiltinAttributes.td"
include "mlir/IR/OpBase.td"
include "mlir/IR/SymbolInterfaces.td"
include "nodal/Dialect/Nodal/NodalDialect.td"
include "nodal/Dialect/Nodal/NodalTypes.td"

class Nodal_Op<string mnemonic, list<Trait> traits = []>
    : Op<Nodal_Dialect, mnemonic, traits>;

//===----------------------------------------------------------------------===//
// Increment 41: isolated pure analog function declarations and resolved calls.
// Body expressions deliberately do not relax existing analog-region traits.
//===----------------------------------------------------------------------===//

def Nodal_AnalogUserFunctionOp : Nodal_Op<"analog_user_function",
    [HasParent<"ModuleOp">, IsolatedFromAbove, Symbol, NoTerminator]> {
  let summary = "Module-local typed pure analog function";
  let arguments = (ins SymbolNameAttr:$sym_name, TypeAttr:$return_type,
    StrAttr:$contract_version, DictionaryAttr:$metadata);
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogFunctionValueOp : Nodal_Op<"analog_function_value",
    [HasParent<"AnalogUserFunctionOp">]> {
  let summary = "Pure typed argument, local or scalar function-body expression";
  let arguments = (ins Variadic<AnyType>:$inputs, StrAttr:$kind,
    OptionalAttr<StrAttr>:$name, OptionalAttr<I64Attr>:$argument_index,
    OptionalAttr<AnyAttr>:$value, OptionalAttr<StrAttr>:$function_id,
    DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogFunctionReturnOp : Nodal_Op<"analog_function_return",
    [HasParent<"AnalogUserFunctionOp">]> {
  let summary = "Total, final analog function return";
  let arguments = (ins AnyType:$value, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

def Nodal_AnalogUserCallOp : Nodal_Op<"analog_user_call"> {
  let summary = "Exact typed module-local nonrecursive analog function call";
  let arguments = (ins Variadic<AnyType>:$inputs, FlatSymbolRefAttr:$callee,
    StrAttr:$contract_version, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Increment 18 compatibility fixture
//===----------------------------------------------------------------------===//

def Nodal_PlaceholderOp : Nodal_Op<"placeholder"> {
  let summary = "Verified semantics-free dialect bootstrap operation";
  let description = [{
    `nodal.placeholder` proves dialect registration, generated
    parser/printer integration, and operation verification. It deliberately
    carries no hardware, timing, hierarchy, analog, scheduling, or backend
    semantics.

    The required `label` is diagnostic bootstrap metadata and must be
    non-empty.
  }];

  let arguments = (ins StrAttr:$label);
  let assemblyFormat = "$label attr-dict";
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Symbols, modules, ports, parameters, and instances
//===----------------------------------------------------------------------===//

def Nodal_ModuleOp : Nodal_Op<"module",
    [IsolatedFromAbove, Symbol, SymbolTable, NoTerminator]> {
  let summary = "Target-neutral Nodal hardware module";
  let arguments = (ins SymbolNameAttr:$sym_name, DictionaryAttr:$metadata);
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_PortOp : Nodal_Op<"port", [HasParent<"ModuleOp">, Symbol]> {
  let summary = "Typed module boundary port";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    TypeAttr:$type,
    StrAttr:$direction,
    FlatSymbolRefAttr:$domain,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_PortValueOp : Nodal_Op<"port_value", [HasParent<"ModuleOp">]> {
  let summary = "Typed SSA use of one module port";
  let arguments = (ins
    FlatSymbolRefAttr:$port,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ParameterOp : Nodal_Op<"parameter",
    [HasParent<"ModuleOp">, Symbol]> {
  let summary = "Typed target-visible ordinary or structural parameter";
  let description = [{
    `variability` retains the existing fixed/symbolic target-visibility contract.
    The optional `parameter_kind`, `classification`, and `unit` attributes add
    the Increment 29 semantic model without invalidating legacy IR. New IR uses
    real/integer/boolean kinds and ordinary/structural classifications.
  }];
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    TypeAttr:$type,
    AnyAttr:$default_value,
    StrAttr:$variability,
    OptionalAttr<StrAttr>:$parameter_kind,
    OptionalAttr<StrAttr>:$classification,
    OptionalAttr<FlatSymbolRefAttr>:$unit,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

// Exact F54 declaration/connection carriers. Clocked update execution, memory
// access operations and target layout remain with their later semantic owners.
def Nodal_DigitalStorageOp : Nodal_Op<"digital_storage", [HasParent<"ModuleOp">]> {
  let summary = "Owned typed structural wire or domain-owned register";
  let arguments = (ins Variadic<AnyType>:$initial, StrAttr:$name,
    StrAttr:$kind, OptionalAttr<FlatSymbolRefAttr>:$domain, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_MemoryOp : Nodal_Op<"memory", [HasParent<"ModuleOp">, Symbol]> {
  let summary = "Explicit typed addressable memory declaration and access policy";
  let arguments = (ins SymbolNameAttr:$sym_name, TypeAttr:$element_type, StrAttr:$depth,
    I64Attr:$read_latency, StrAttr:$read_under_write, StrAttr:$ordering,
    FlatSymbolRefAttr:$domain, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

// F56 owned memory ports. Reads are continuously enabled intrinsic state ports;
// write ingress receives its actual guard/clock/order from SequentialModel.
def Nodal_MemoryReadOp : Nodal_Op<"memory_read", [HasParent<"ModuleOp">]> {
  let summary = "Owned memory read with intrinsic exact latency and collision semantics";
  let arguments = (ins AnyType:$address, FlatSymbolRefAttr:$memory,
    FlatSymbolRefAttr:$domain, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}
def Nodal_MemoryWriteOp : Nodal_Op<"memory_write",
    [ParentOneOf<["ModuleOp", "BehavioralIfOp", "FlatFSMOp", "EnumSwitchOp"]>]> {
  let summary = "Typed owned memory write before checked path/order normalization";
  let arguments = (ins AnyType:$address, AnyType:$value, AnyType:$mask,
    FlatSymbolRefAttr:$memory, FlatSymbolRefAttr:$domain, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}
def Nodal_MemoryWriteEffectOp : Nodal_Op<"memory_write_effect", [HasParent<"ModuleOp">]> {
  let summary = "Canonical pre-edge guarded write in authored per-memory port order";
  let arguments = (ins AnyType:$address, AnyType:$value, AnyType:$mask,
    AnyType:$guard, AnyType:$clock, FlatSymbolRefAttr:$memory, FlatSymbolRefAttr:$domain,
    StrAttr:$edge, I64Attr:$order, StrAttr:$root_id, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

def Nodal_DigitalAssignOp : Nodal_Op<"digital_assign", [ParentOneOf<["ModuleOp", "HardwareLoopOp", "GenerateOp", "StructuralIfOp", "BehavioralIfOp", "FlatFSMOp", "EnumSwitchOp"]>]> {
  let summary = "Exact typed continuous connection or register-next intent";
  let arguments = (ins AnyType:$target, AnyType:$value, StrAttr:$kind, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

def Nodal_InstanceOp : Nodal_Op<"instance",
    [ParentOneOf<["ModuleOp", "GenerateOp"]>, Symbol]> {
  let summary = "Typed module instance with explicit bindings";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    FlatSymbolRefAttr:$module,
    DictionaryAttr:$parameter_bindings,
    DictionaryAttr:$domain_bindings,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_InstanceTerminalOp : Nodal_Op<"instance_terminal",
    [ParentOneOf<["ModuleOp", "GenerateOp"]>]> {
  let summary = "Typed immediate-child conservative terminal reference";
  let description = [{
    Resolves one declared boundary terminal of one direct child instance. The
    result is a parent-owned connectivity endpoint; it never exposes child
    internal nodes or descendant terminals.
  }];
  let arguments = (ins
    FlatSymbolRefAttr:$instance,
    StrAttr:$port,
    StrAttr:$name,
    StrAttr:$direction,
    StrAttr:$flow_orientation,
    StrAttr:$source_path,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs Nodal_TerminalType:$result);
  let hasVerifier = 1;
}

def Nodal_InstanceTerminalArrayOp : Nodal_Op<"instance_terminal_array",
    [ParentOneOf<["ModuleOp", "GenerateOp"]>]> {
  let summary = "Whole immediate-child conservative array port reference";
  let arguments = (ins FlatSymbolRefAttr:$instance, StrAttr:$port, StrAttr:$name,
    StrAttr:$direction, StrAttr:$flow_orientation, StrAttr:$source_path,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Interface, role, protocol, and logical ABI model
//===----------------------------------------------------------------------===//

def Nodal_InterfaceOp : Nodal_Op<"interface",
    [IsolatedFromAbove, Symbol, SymbolTable, NoTerminator]> {
  let summary = "Logical non-storable Interface definition";
  let arguments = (ins SymbolNameAttr:$sym_name, DictionaryAttr:$metadata);
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_InterfaceRoleOp : Nodal_Op<"interface_role",
    [HasParent<"InterfaceOp">, Symbol]> {
  let summary = "Named boundary role inside an Interface definition";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    StrAttr:$kind,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_InterfaceMemberOp : Nodal_Op<"interface_member",
    [HasParent<"InterfaceOp">, Symbol]> {
  let summary = "Typed Interface member with protocol and role ownership";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    TypeAttr:$type,
    StrAttr:$protocol,
    ArrayAttr:$roles,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_InterfaceInstanceOp : Nodal_Op<"interface_instance",
    [HasParent<"ModuleOp">, Symbol]> {
  let summary = "Physical instance of one logical Interface definition";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    FlatSymbolRefAttr:$definition,
    StrAttr:$role,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_MemberAccessOp : Nodal_Op<"member_access"> {
  let summary = "Typed access to one logical Interface member path";
  let arguments = (ins
    FlatSymbolRefAttr:$instance,
    StrAttr:$path,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_InterfaceAbiOp : Nodal_Op<"interface_abi",
    [HasParent<"ModuleOp">]> {
  let summary = "Target-neutral logical Interface ABI metadata";
  let arguments = (ins
    StrAttr:$logical_path,
    ArrayAttr:$members,
    StrAttr:$layout_policy,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

// Fully typed connectivity uses existing Interface/Role/Member identities.
def Nodal_InterfaceConnectOp : Nodal_Op<"interface_connect", [HasParent<"ModuleOp">]> {
  let summary = "Connect two exact role-qualified Interface leaves";
  let arguments = (ins AnyType:$left, AnyType:$right, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}
def Nodal_InterfaceDriveOp : Nodal_Op<"interface_drive", [HasParent<"ModuleOp">]> {
  let summary = "Drive an explicitly writable Interface leaf";
  let arguments = (ins AnyType:$member, AnyType:$value, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}
def Nodal_InterfaceObserveOp : Nodal_Op<"interface_observe", [HasParent<"ModuleOp">]> {
  let summary = "Observe a role-qualified digital Interface leaf";
  let arguments = (ins AnyType:$member, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Clock/reset domain identity and relationships
//===----------------------------------------------------------------------===//

def Nodal_DomainOp : Nodal_Op<"domain", [HasParent<"ModuleOp">, Symbol]> {
  let summary = "Clock/reset domain declaration";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    StrAttr:$edge,
    StrAttr:$reset_policy,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_DomainRequirementOp : Nodal_Op<"domain_requirement",
    [HasParent<"ModuleOp">, Symbol]> {
  let summary = "Typed reusable-module domain requirement";
  let arguments = (ins SymbolNameAttr:$sym_name, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

def Nodal_DomainBindOp : Nodal_Op<"domain_bind", [HasParent<"ModuleOp">]> {
  let summary = "Explicit binding of one domain requirement";
  let arguments = (ins
    FlatSymbolRefAttr:$requirement,
    FlatSymbolRefAttr:$actual,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_ClockRelationOp : Nodal_Op<"clock_relation",
    [HasParent<"ModuleOp">]> {
  let summary = "Declared relationship between two clock domains";
  let arguments = (ins
    FlatSymbolRefAttr:$source,
    FlatSymbolRefAttr:$destination,
    StrAttr:$kind,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_ResetRelationOp : Nodal_Op<"reset_relation",
    [HasParent<"ModuleOp">]> {
  let summary = "Declared relationship between two reset domains";
  let arguments = (ins
    FlatSymbolRefAttr:$source,
    FlatSymbolRefAttr:$destination,
    StrAttr:$kind,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}


//===----------------------------------------------------------------------===//
// Units, compile-time expressions, constraints, overrides, and envelopes
//===----------------------------------------------------------------------===//

def Nodal_UnitOp : Nodal_Op<"unit", [Symbol]> {
  let summary = "Canonical physical-unit declaration for parameter literals";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    StrAttr:$symbol,
    StrAttr:$dimension,
    F64Attr:$scale,
    StrAttr:$native_suffix,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_ConstLiteralOp : Nodal_Op<"const_literal"> {
  let summary = "Losslessly spelled compile-time scalar literal";
  let arguments = (ins
    AnyAttr:$value,
    StrAttr:$spelling,
    OptionalAttr<FlatSymbolRefAttr>:$unit,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ConstParameterRefOp : Nodal_Op<"const_parameter_ref"> {
  let summary = "Compile-time reference to a module parameter";
  let arguments = (ins
    FlatSymbolRefAttr:$parameter,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ConstExprOp : Nodal_Op<"const_expr"> {
  let summary = "Typed target-neutral compile-time scalar expression";
  let arguments = (ins
    Variadic<AnyType>:$operands,
    StrAttr:$operator_name,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ParameterValueOp : Nodal_Op<"parameter_value",
    [HasParent<"ModuleOp">]> {
  let summary = "Lossless default-expression attachment for one parameter";
  let arguments = (ins
    AnyType:$value,
    FlatSymbolRefAttr:$parameter,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_ParameterConstraintOp : Nodal_Op<"parameter_constraint",
    [HasParent<"ModuleOp">]> {
  let summary = "Native range or exclusion constraint for one parameter";
  let arguments = (ins
    Variadic<AnyType>:$values,
    FlatSymbolRefAttr:$parameter,
    StrAttr:$constraint_kind,
    BoolAttr:$lower_inclusive,
    BoolAttr:$upper_inclusive,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_ParameterOverrideOp : Nodal_Op<"parameter_override",
    [ParentOneOf<["ModuleOp", "GenerateOp"]>]> {
  let summary = "Lossless typed override of an instantiated module parameter";
  let arguments = (ins
    AnyType:$value,
    FlatSymbolRefAttr:$instance,
    FlatSymbolRefAttr:$parameter,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_ParameterEnvelopeOp : Nodal_Op<"parameter_envelope",
    [HasParent<"ModuleOp">]> {
  let summary = "Static structural-effect envelope for one parameter";
  let arguments = (ins
    FlatSymbolRefAttr:$parameter,
    ArrayAttr:$effects,
    StrAttr:$policy,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_DynamicValueOp : Nodal_Op<"dynamic_value"> {
  let summary = "Explicit marker for a runtime value excluded from constant evaluation";
  let arguments = (ins
    AnyType:$input,
    StrAttr:$origin,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Constants, origin metadata, shape, and structural iteration
//===----------------------------------------------------------------------===//

def Nodal_ConstantOp : Nodal_Op<"constant"> {
  let summary = "Target-neutral typed constant";
  let arguments = (ins AnyAttr:$value, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ShapeIndexOp : Nodal_Op<"shape_index"> {
  let summary = "Canonical multidimensional shaped-value indexing";
  let arguments = (ins
    AnyType:$input,
    Variadic<AnyType>:$indices,
    OptionalAttr<StrAttr>:$owner,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ShapeFlattenOp : Nodal_Op<"shape_flatten"> {
  let summary = "Explicit semantic flattening view with separate layout policy";
  let arguments = (ins
    AnyType:$input,
    StrAttr:$layout,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ShapeViewOp : Nodal_Op<"shape_view"> {
  let summary = "Source-correlated shaped view and materialization boundary";
  let arguments = (ins
    AnyType:$input,
    StrAttr:$dimensions,
    StrAttr:$origin,
    StrAttr:$materialization,
    StrAttr:$observability,
    OptionalAttr<StrAttr>:$owner,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ShapeSliceOp : Nodal_Op<"shape_slice"> {
  let summary = "Read-only half-open unit-stride structural shape slice";
  let arguments = (ins
    AnyType:$input,
    I64:$lower,
    I64:$upperExclusive,
    I64:$length,
    I64Attr:$axis,
    StrAttr:$dimensions,
    StrAttr:$origin,
    StrAttr:$observability,
    OptionalAttr<StrAttr>:$owner,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_GenerateOp : Nodal_Op<"generate", [SymbolTable, NoTerminator]> {
  let summary = "Symbolic structural generation region";
  let arguments = (ins
    Variadic<I64>:$bounds,
    StrAttr:$induction,
    OptionalAttr<AnyAttr>:$lower,
    OptionalAttr<AnyAttr>:$upper,
    OptionalAttr<AnyAttr>:$step,
    OptionalAttr<StrAttr>:$region_id,
    OptionalAttr<StrAttr>:$induction_path,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_HardwareLoopOp : Nodal_Op<"hardware_loop", [NoTerminator]> {
  let summary = "Legacy carrier or versioned finite ordered digital iteration";
  let arguments = (ins
    Variadic<AnyType>:$inputs,
    StrAttr:$induction,
    OptionalAttr<AnyAttr>:$lower,
    OptionalAttr<AnyAttr>:$upper,
    OptionalAttr<AnyAttr>:$step,
    StrAttr:$effect_policy,
    OptionalAttr<StrAttr>:$contract_version,
    OptionalAttr<StrAttr>:$region_id,
    OptionalAttr<StrAttr>:$induction_path,
    OptionalAttr<I64Attr>:$maximum,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_HardwareLoopEndOp : Nodal_Op<"hardware_loop_end",
    [Terminator, HasParent<"HardwareLoopOp">]> {
  let summary = "Explicit end of a bounded digital loop template";
  let hasVerifier = 1;
}

def Nodal_IterationIndexOp : Nodal_Op<"iteration_index"> {
  let summary = "Actual affine i64 induction plus or minus a static offset";
  let arguments = (ins I64:$lhs, I64:$rhs, StrAttr:$operator_name,
    DictionaryAttr:$metadata);
  let results = (outs I64:$result);
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Digital resolved nets
//===----------------------------------------------------------------------===//

def Nodal_ResolvedNetOp : Nodal_Op<"resolved_net",
    [HasParent<"ModuleOp">]> {
  let summary = "Resolved digital net identity";
  let arguments = (ins StrAttr:$name, DictionaryAttr:$metadata);
  let results = (outs Nodal_ResolvedType:$result);
  let hasVerifier = 1;
}

def Nodal_NetReadOp : Nodal_Op<"net_read"> {
  let summary = "Read the resolved value of a digital net";
  let arguments = (ins Nodal_ResolvedType:$net, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_NetDriverOp : Nodal_Op<"net_driver"> {
  let summary = "Create one stable typed driver identity";
  let arguments = (ins Nodal_ResolvedType:$net, StrAttr:$driver_id,
                       DictionaryAttr:$metadata);
  let results = (outs Nodal_DriverType:$result);
  let hasVerifier = 1;
}

def Nodal_NetDriveOp : Nodal_Op<"net_drive"> {
  let summary = "Drive a resolved net through one explicit driver";
  let arguments = (ins
    Nodal_ResolvedType:$net,
    Nodal_DriverType:$driver,
    AnyType:$value,
    AnyType:$enable,
    StrAttr:$mode,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}


def Nodal_NetReleaseOp : Nodal_Op<"net_release", [HasParent<"ModuleOp">]> {
  let summary = "Explicit inactive high-impedance driver";
  let arguments = (ins Nodal_ResolvedType:$net, Nodal_DriverType:$driver,
                       DictionaryAttr:$metadata);
  let hasVerifier = 1;
}
def Nodal_NetDriveLevelOp : Nodal_Op<"net_drive_level", [HasParent<"ModuleOp">]> {
  let summary = "Conditional low or high drive with no fabricated bit-vector constant";
  let arguments = (ins Nodal_ResolvedType:$net, Nodal_DriverType:$driver,
                       AnyType:$enable, StrAttr:$level, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}
def Nodal_NetPassThroughOp : Nodal_Op<"net_pass_through", [HasParent<"ModuleOp">]> {
  let summary = "Exact resolved-net identity connection";
  let arguments = (ins Nodal_ResolvedType:$left, Nodal_ResolvedType:$right,
                       DictionaryAttr:$metadata);
  let hasVerifier = 1;
}
def Nodal_NetSplitOp : Nodal_Op<"net_split", [HasParent<"ModuleOp">]> {
  let summary = "Typed write and enable views of one unique authored conditional driver";
  let arguments = (ins Nodal_ResolvedType:$net, DictionaryAttr:$metadata);
  let results = (outs AnyType:$value, AnyType:$enable);
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Analog natures, disciplines, and normalized imports
//===----------------------------------------------------------------------===//

def Nodal_NatureOp : Nodal_Op<"nature", [Symbol]> {
  let summary = "Canonical analog nature declaration";
  let description = [{
    A nature records a canonical unit spelling, one access-function identity,
    and a positive finite absolute tolerance. Nature identity remains nominal;
    imports resolve to one canonical declaration before compatibility checks.
  }];
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    StrAttr:$units,
    StrAttr:$access,
    F64Attr:$abstol,
    OptionalAttr<StrAttr>:$dimension,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_NatureImportOp : Nodal_Op<"nature_import", [Symbol]> {
  let summary = "Hash-pinned local alias of a canonical nature declaration";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    FlatSymbolRefAttr:$target,
    StrAttr:$source,
    StrAttr:$definition_hash,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_DisciplineOp : Nodal_Op<"discipline", [Symbol]> {
  let summary = "Analog discipline declaration";
  let description = [{
    A discipline selects a continuous or discrete domain, one potential
    nature, and an optional flow nature. Absence of flow denotes a
    signal-flow discipline; presence of both denotes a conservative
    discipline.
  }];
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    StrAttr:$domain,
    FlatSymbolRefAttr:$potential,
    OptionalAttr<FlatSymbolRefAttr>:$flow,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_DisciplineImportOp : Nodal_Op<"discipline_import", [Symbol]> {
  let summary = "Hash-pinned local alias of a canonical discipline declaration";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    FlatSymbolRefAttr:$target,
    StrAttr:$source,
    StrAttr:$definition_hash,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Conservative connectivity and explicit mixed-signal bridges
//===----------------------------------------------------------------------===//

def Nodal_ComponentContractOp : Nodal_Op<"component_contract",
    [HasParent<"ModuleOp">]> {
  let summary = "Partial or concrete physical-component connectivity contract";
  let arguments = (ins
    StrAttr:$kind,
    StrAttr:$connectivity_ownership,
    StrAttr:$source_path,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_TerminalOp : Nodal_Op<"terminal", [HasParent<"ModuleOp">]> {
  let summary = "Boundary conservative terminal";
  let description = [{
    A boundary terminal retains Verilog-AMS-compatible input, output, or inout
    direction as interface metadata. Direction never creates causal signal-flow
    semantics. `flow_orientation` independently defines the sign used by local
    conservative flow equations.
  }];
  let arguments = (ins
    StrAttr:$name,
    OptionalAttr<StrAttr>:$direction,
    OptionalAttr<StrAttr>:$flow_orientation,
    OptionalAttr<StrAttr>:$source_path,
    DictionaryAttr:$metadata
  );
  let results = (outs Nodal_TerminalType:$result);
  let hasVerifier = 1;
}

def Nodal_NodeOp : Nodal_Op<"node", [ParentOneOf<["ModuleOp", "GenerateOp"]>]> {
  let summary = "Internal conservative node";
  let arguments = (ins
    StrAttr:$name,
    OptionalAttr<StrAttr>:$source_path,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs Nodal_TerminalType:$result);
  let hasVerifier = 1;
}

def Nodal_NodeArrayOp : Nodal_Op<"node_array", [ParentOneOf<["ModuleOp", "GenerateOp"]>]> {
  let summary = "Owned structural array of internal electrical nodes";
  let arguments = (ins StrAttr:$name, StrAttr:$source_path,
    OptionalAttr<StrAttr>:$generated_owner, OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_TerminalArrayOp : Nodal_Op<"terminal_array", [HasParent<"ModuleOp">]> {
  let summary = "Owned structural conservative boundary array";
  let arguments = (ins StrAttr:$name, StrAttr:$direction, StrAttr:$flow_orientation,
    StrAttr:$source_path, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ConnectArrayOp : Nodal_Op<"connect_array", [ParentOneOf<["ModuleOp", "GenerateOp"]>]> {
  let summary = "Symmetric exact conservative endpoint relation";
  let arguments = (ins AnyType:$left, AnyType:$right, StrAttr:$connection_id,
    StrAttr:$source_path, OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

def Nodal_ConnectOp : Nodal_Op<"connect", [ParentOneOf<["ModuleOp", "GenerateOp"]>]> {
  let summary = "Source-semantic conservative connection relation";
  let arguments = (ins
    Variadic<Nodal_TerminalType>:$members,
    StrAttr:$connection_id,
    StrAttr:$source_path,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_AliasOp : Nodal_Op<"alias", [HasParent<"ModuleOp">]> {
  let summary = "Exact conservative endpoint alias";
  let arguments = (ins
    Nodal_TerminalType:$lhs,
    Nodal_TerminalType:$rhs,
    StrAttr:$alias_id,
    StrAttr:$source_path,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_ReferenceOp : Nodal_Op<"reference", [HasParent<"ModuleOp">]> {
  let summary = "Module or global conservative reference declaration";
  let arguments = (ins
    Nodal_TerminalType:$member,
    StrAttr:$scope,
    StrAttr:$source_path,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_BranchOp : Nodal_Op<"branch"> {
  let summary = "Directed conservative branch between compatible terminals";
  let description = [{
    Flow is positive from `positive` to `negative`. A named branch carries a
    source name; an implicit branch is uniquely identified by its unordered
    endpoint pair while retaining the authored orientation.
  }];
  let arguments = (ins
    Nodal_TerminalType:$positive,
    Nodal_TerminalType:$negative,
    OptionalAttr<StrAttr>:$name,
    OptionalAttr<StrAttr>:$declaration_kind,
    OptionalAttr<StrAttr>:$source_path,
    DictionaryAttr:$metadata
  );
  let results = (outs Nodal_BranchType:$result);
  let hasVerifier = 1;
}

def Nodal_ConnectionSetOp : Nodal_Op<"connection_set",
    [HasParent<"ModuleOp">, Symbol]> {
  let summary = "Compiler-owned normalized conservative connection set";
  let arguments = (ins
    Variadic<Nodal_TerminalType>:$members,
    SymbolNameAttr:$sym_name,
    FlatSymbolRefAttr:$discipline,
    StrAttr:$ownership,
    BoolAttr:$complete,
    BoolAttr:$reference,
    StrAttr:$reference_scope,
    StrAttr:$reference_identity,
    ArrayAttr:$member_paths,
    ArrayAttr:$provenance,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_PotentialEqualityOp : Nodal_Op<"potential_equality",
    [HasParent<"ModuleOp">]> {
  let summary = "Generated compatible-potential equality";
  let arguments = (ins
    Nodal_TerminalType:$lhs,
    Nodal_TerminalType:$rhs,
    FlatSymbolRefAttr:$connection_set,
    ArrayAttr:$provenance,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_ReferencePotentialOp : Nodal_Op<"reference_potential",
    [HasParent<"ModuleOp">]> {
  let summary = "Generated zero-potential reference identity";
  let arguments = (ins
    Nodal_TerminalType:$member,
    FlatSymbolRefAttr:$connection_set,
    StrAttr:$reference_identity,
    ArrayAttr:$provenance,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_FlowConservationOp : Nodal_Op<"flow_conservation",
    [HasParent<"ModuleOp">]> {
  let summary = "Generated signed zero-sum conservative flow equation";
  let arguments = (ins
    Variadic<AnyType>:$terms,
    ArrayAttr:$signs,
    FlatSymbolRefAttr:$connection_set,
    StrAttr:$ownership,
    BoolAttr:$complete,
    ArrayAttr:$provenance,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_AccessOp : Nodal_Op<"access"> {
  let summary = "Potential or flow access on a conservative branch";
  let arguments = (ins
    Nodal_BranchType:$branch,
    StrAttr:$kind,
    OptionalAttr<StrAttr>:$function,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_TerminalAccessOp
    : Nodal_Op<"terminal_access", [HasParent<"AnalogOp">]> {
  let summary = "One-terminal or oriented two-terminal nature access";
  let arguments = (ins
    Variadic<Nodal_TerminalType>:$terminals,
    StrAttr:$kind,
    StrAttr:$function,
    OptionalAttr<StrAttr>:$reference_identity,
    OptionalAttr<StrAttr>:$source_path,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_PortFlowAccessOp
    : Nodal_Op<"port_flow_access", [HasParent<"AnalogOp">]> {
  let summary = "Angle-delimited total-flow access on a local boundary terminal";
  let arguments = (ins
    Nodal_TerminalType:$port,
    StrAttr:$kind,
    StrAttr:$function,
    OptionalAttr<StrAttr>:$source_path,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ProbeOp : Nodal_Op<"probe", [ParentOneOf<["ModuleOp", "GenerateOp"]>]> {
  let summary = "Compiler-owned source-free potential or flow probe intent";
  let arguments = (ins
    Variadic<AnyType>:$subject,
    StrAttr:$form,
    StrAttr:$kind,
    StrAttr:$constraint_intent,
    ArrayAttr:$provenance,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_BridgeOp : Nodal_Op<"bridge"> {
  let summary = "Explicit analog/digital or conservative/signal-flow bridge";
  let arguments = (ins
    AnyType:$source,
    StrAttr:$kind,
    StrAttr:$source_domain,
    StrAttr:$destination_domain,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Minimal analog expressions and contributions
//===----------------------------------------------------------------------===//

def Nodal_AnalogOp : Nodal_Op<"analog", [ParentOneOf<["ModuleOp", "GenerateOp"]>, NoTerminator]> {
  let summary = "Continuous-time analog equation region";
  let arguments = (ins OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction, DictionaryAttr:$metadata,
    OptionalAttr<DictionaryAttr>:$source_equations);
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

// Source-semantic algebraic checkpoint; no Pure traits and no target assignment.
def Nodal_AnalogUnknownOp : Nodal_Op<"analog_unknown", [HasParent<"AnalogOp">]> {
  let summary = "Explicit owned scalar source unknown with separate initialization";
  let arguments = (ins Variadic<AnyType>:$initializer, StrAttr:$state_id,
    StrAttr:$owner, StrAttr:$component, StrAttr:$initialization, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogEquationOp : Nodal_Op<"analog_equation", [HasParent<"AnalogOp">]> {
  let summary = "Unordered typed source equation preserving both authored sides";
  let arguments = (ins AnyType:$lhs, AnyType:$rhs, StrAttr:$equation_id,
    StrAttr:$owner, StrAttr:$component, BoolAttr:$initial_only,
    ArrayAttr:$analyses, StrAttr:$continuity, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

def Nodal_AnalogProcedureOp
    : Nodal_Op<"analog_procedure", [HasParent<"AnalogOp">, NoTerminator]> {
  let summary = "Ordered component-local analog procedural region";
  let arguments = (ins StrAttr:$owner, OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction, DictionaryAttr:$metadata);
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogHeldReadOp : Nodal_Op<"analog_held_read", [HasParent<"AnalogOp">]> {
  let summary = "Continuous read of independently proven event-held real storage";
  let arguments = (ins StrAttr:$variable, StrAttr:$owner, DictionaryAttr:$metadata);
  let results = (outs F64:$result);
  let hasVerifier = 1;
}

// No Pure trait: an unused monitor must retain timestep/history effects.
class Nodal_AnalogEventOp<string mnemonic> : Nodal_Op<mnemonic> {
  let summary = "Source-semantic analog event monitor or analysis lifecycle event";
  let arguments = (ins Variadic<AnyTypeOf<[Nodal_VariableType, Nodal_AnalogDeferredType]>>:$references,
    OptionalAttr<StrAttr>:$reference_form,
    StrAttr:$event_id, StrAttr:$owner, StrAttr:$contract,
    StrAttr:$name, OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction, ArrayAttr:$arguments, ArrayAttr:$analyses,
    ArrayAttr:$event_reads, DictionaryAttr:$metadata);
  let results = (outs Nodal_AnalogEventType:$result);
  let hasVerifier = 1;
}
def Nodal_AnalogCrossOp : Nodal_AnalogEventOp<"analog_cross">;
def Nodal_AnalogAboveOp : Nodal_AnalogEventOp<"analog_above">;
def Nodal_AnalogTimerOp : Nodal_AnalogEventOp<"analog_timer">;
def Nodal_AnalogInitialStepOp : Nodal_AnalogEventOp<"analog_initial_step">;
def Nodal_AnalogFinalStepOp : Nodal_AnalogEventOp<"analog_final_step">;

def Nodal_AnalogEventOrOp : Nodal_Op<"analog_event_or"> {
  let summary = "Any-of event composition with one controlled body evaluation";
  let arguments = (ins Variadic<Nodal_AnalogEventType>:$events,
    StrAttr:$event_id, StrAttr:$owner, StrAttr:$contract, StrAttr:$name,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction, DictionaryAttr:$metadata);
  let results = (outs Nodal_AnalogEventType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogOnOp : Nodal_Op<"analog_on", [NoTerminator]> {
  let summary = "Nonblocking analog event-controlled statement region";
  let arguments = (ins Nodal_AnalogEventType:$event,
    StrAttr:$statement_id, StrAttr:$owner, OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction, DictionaryAttr:$metadata);
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogScopeOp : Nodal_Op<"analog_scope", [NoTerminator]> {
  let summary = "Lexical scope nested inside an analog procedural region";
  let arguments = (ins
    StrAttr:$scope_id,
    StrAttr:$owner,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}


def Nodal_AnalogIfOp : Nodal_Op<"analog_if", [NoTerminator]> {
  let summary = "First-match structured analog conditional";
  let arguments = (ins
    StrAttr:$statement_id,
    StrAttr:$owner,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogIfArmOp
    : Nodal_Op<"analog_if_arm", [HasParent<"AnalogIfOp">, NoTerminator]> {
  let summary = "One retained arm of a structured analog conditional";
  let arguments = (ins
    Variadic<AnyTypeOf<[Nodal_VariableType, Nodal_AnalogDeferredType]>>:$references,
    OptionalAttr<StrAttr>:$reference_form,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    StrAttr:$arm_id,
    StrAttr:$owner,
    BoolAttr:$is_else,
    StrAttr:$stage,
    StrAttr:$condition_value,
    StrAttr:$condition_kind,
    StrAttr:$condition_dimension,
    ArrayAttr:$condition_reads,
    BoolAttr:$static_value_present,
    BoolAttr:$static_value,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogCaseOp : Nodal_Op<"analog_case", [NoTerminator]> {
  let summary = "Exact non-fall-through structured analog case selection";
  let arguments = (ins
    Variadic<AnyTypeOf<[Nodal_VariableType, Nodal_AnalogDeferredType]>>:$references,
    OptionalAttr<StrAttr>:$reference_form,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    StrAttr:$statement_id,
    StrAttr:$owner,
    StrAttr:$selector_value,
    StrAttr:$selector_kind,
    StrAttr:$selector_dimension,
    ArrayAttr:$selector_reads,
    BoolAttr:$static_value_present,
    StrAttr:$static_value,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogCaseArmOp
    : Nodal_Op<"analog_case_arm", [HasParent<"AnalogCaseOp">, NoTerminator]> {
  let summary = "One exact non-fall-through analog case arm";
  let arguments = (ins
    StrAttr:$arm_id,
    StrAttr:$owner,
    BoolAttr:$is_default,
    ArrayAttr:$labels,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogLoopOp : Nodal_Op<"analog_loop", [NoTerminator]> {
  let summary = "Exact static or finite runtime-bounded analog loop";
  let arguments = (ins
    Variadic<AnyTypeOf<[Nodal_VariableType, Nodal_AnalogDeferredType]>>:$references,
    OptionalAttr<StrAttr>:$reference_form,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    StrAttr:$statement_id,
    StrAttr:$owner,
    StrAttr:$stage,
    I64Attr:$minimum_iterations,
    I64Attr:$maximum_iterations,
    StrAttr:$bound_value,
    StrAttr:$bound_kind,
    StrAttr:$bound_dimension,
    ArrayAttr:$bound_reads,
    BoolAttr:$static_trip_count_present,
    I64Attr:$static_trip_count,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogBreakOp : Nodal_Op<"analog_break"> {
  let summary = "Exit the nearest runtime-bounded analog loop";
  let arguments = (ins
    StrAttr:$statement_id,
    StrAttr:$owner,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_AnalogContinueOp : Nodal_Op<"analog_continue"> {
  let summary = "Continue the nearest runtime-bounded analog loop";
  let arguments = (ins
    StrAttr:$statement_id,
    StrAttr:$owner,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_AnalogVariableOp : Nodal_Op<"analog_variable"> {
  let summary = "Declaration of one typed analog procedural variable";
  let arguments = (ins
    Variadic<AnyType>:$initializers,
    StrAttr:$identity,
    StrAttr:$owner,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    I64Attr:$declaration_order,
    BoolAttr:$initialized,
    OptionalAttr<StrAttr>:$initializer_form,
    StrAttr:$initializer_value,
    StrAttr:$initializer_kind,
    StrAttr:$initializer_dimension,
    ArrayAttr:$initializer_reads,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogVariableReadOp : Nodal_Op<"analog_variable_read"> {
  let summary = "Read the current value of one analog procedural variable";
  let arguments = (ins
    AnyType:$variable,
    StrAttr:$read_id,
    StrAttr:$owner,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogAssignOp : Nodal_Op<"analog_assign"> {
  let summary = "Authored ordered assignment to one analog procedural variable";
  let arguments = (ins
    AnyType:$variable,
    Variadic<AnyType>:$values,
    OptionalAttr<StrAttr>:$value_form,
    StrAttr:$statement_id,
    I64Attr:$authored_order,
    StrAttr:$owner,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    StrAttr:$value_kind,
    StrAttr:$value_dimension,
    ArrayAttr:$analyses,
    BoolAttr:$guard_present,
    StrAttr:$guard_value,
    StrAttr:$guard_kind,
    StrAttr:$guard_dimension,
    ArrayAttr:$guard_reads,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_AnalogLiveFillOp : Nodal_Op<"analog_live_fill"> {
  let summary = "Non-evaluating actual fill identity for one live consumer";
  let arguments = (ins
    Variadic<AnyTypeOf<[Nodal_VariableType, Nodal_AnalogDeferredType]>>:$references,
    StrAttr:$identity,
    StrAttr:$value,
    StrAttr:$owner,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs Nodal_AnalogDeferredType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogArrayFillOp : Nodal_Op<"analog_array_fill"> {
  let summary = "Ordered scalar evaluation producing a shaped immutable Real value";
  let arguments = (ins
    Variadic<AnyType>:$reads,
    StrAttr:$value,
    StrAttr:$owner,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_RealLiteralOp : Nodal_Op<"real_literal", [HasParent<"AnalogOp">]> {
  let summary = "Finite real literal, optionally with verified source-equation units";
  let arguments = (ins F64Attr:$value, DictionaryAttr:$metadata,
                       OptionalAttr<FlatSymbolRefAttr>:$unit,
                       OptionalAttr<F64Attr>:$authored_value);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogIntegerLiteralOp
    : Nodal_Op<"analog_integer_literal", [HasParent<"AnalogOp">]> {
  let summary = "Exact integer dimensionless literal in an analog expression";
  let arguments = (ins APIntAttr:$value, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ParameterRefOp : Nodal_Op<"parameter_ref", [HasParent<"AnalogOp">]> {
  let summary = "Typed reference to an enclosing scalar parameter";
  let arguments = (ins FlatSymbolRefAttr:$parameter, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogFunctionOp : Nodal_Op<"analog_function", [HasParent<"AnalogOp">]> {
  let summary = "Versioned pure real mathematical function";
  let arguments = (ins Variadic<AnyType>:$inputs, StrAttr:$function_id,
                       StrAttr:$registry_version, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogTransferOp : Nodal_Op<"analog_transfer", [HasParent<"AnalogOp">]> {
  let summary = "Owned Laplace or sampled ND transfer state with immutable coefficient segments";
  let arguments = (ins Variadic<AnyType>:$inputs, StrAttr:$transfer_kind,
                       StrAttr:$contract_version, I64Attr:$numerator_size,
                       I64Attr:$denominator_size, StrAttr:$operator_id, StrAttr:$state_id,
                       StrAttr:$owner, StrAttr:$coefficient_order, StrAttr:$initialization,
                       StrAttr:$result_dimension,
                       OptionalAttr<StrAttr>:$generated_owner,
                       OptionalAttr<StrAttr>:$generated_induction,
                       DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogNoiseOp : Nodal_Op<"analog_noise", [HasParent<"AnalogOp">]> {
  let summary = "Owned small-signal noise source; effectful even with constant or zero power";
  let arguments = (ins Variadic<AnyType>:$inputs, StrAttr:$noise_kind,
                       StrAttr:$contract_version, StrAttr:$noise_name, StrAttr:$source_id,
                       StrAttr:$owner, StrAttr:$correlation, ArrayAttr:$analyses,
                       StrAttr:$result_dimension,
                       OptionalAttr<StrAttr>:$generated_owner,
                       OptionalAttr<StrAttr>:$generated_induction,
                       DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogAnalysisOp : Nodal_Op<"analog_analysis", [HasParent<"AnalogOp">]> {
  let summary = "Dynamic simulator analysis query, never an elaboration constant";
  let arguments = (ins StrAttr:$analysis_kind, StrAttr:$registry_version, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogContextOp : Nodal_Op<"analog_context", [HasParent<"AnalogOp">]> {
  let summary = "Typed effectful simulator context query, never an elaboration constant";
  let arguments = (ins StrAttr:$query_kind, StrAttr:$registry_version,
                       OptionalAttr<StrAttr>:$generated_owner,
                       OptionalAttr<StrAttr>:$generated_induction,
                       DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

class Nodal_AnalogBinaryOp<string mnemonic, string summaryText>
    : Nodal_Op<mnemonic, [HasParent<"AnalogOp">]> {
  let summary = summaryText;
  let arguments = (ins AnyType:$lhs, AnyType:$rhs, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogAddOp : Nodal_AnalogBinaryOp<"analog_add", "Analog quantity addition">;
def Nodal_AnalogSubOp : Nodal_AnalogBinaryOp<"analog_sub", "Analog quantity subtraction">;
def Nodal_AnalogMulOp : Nodal_AnalogBinaryOp<"analog_mul", "Analog quantity multiplication">;
def Nodal_AnalogDivOp : Nodal_AnalogBinaryOp<"analog_div", "Analog quantity division">;

def Nodal_AnalogNegOp : Nodal_Op<"analog_neg", [HasParent<"AnalogOp">]> {
  let summary = "Analog numeric negation";
  let arguments = (ins AnyType:$input, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogCompareOp : Nodal_Op<"analog_compare", [HasParent<"AnalogOp">]> {
  let summary = "Dimension-aware analog or Boolean comparison";
  let arguments = (ins
    AnyType:$lhs,
    AnyType:$rhs,
    StrAttr:$predicate,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogLogicOp : Nodal_Op<"analog_logic", [HasParent<"AnalogOp">]> {
  let summary = "Boolean-only logical expression";
  let arguments = (ins
    Variadic<AnyType>:$operands,
    StrAttr:$operator_name,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogSelectOp : Nodal_Op<"analog_select", [HasParent<"AnalogOp">]> {
  let summary = "Typed conditional analog or Boolean expression";
  let arguments = (ins
    AnyType:$condition,
    AnyType:$true_value,
    AnyType:$false_value,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogDdtOp : Nodal_Op<"analog_ddt", [HasParent<"AnalogOp">]> {
  let summary = "Continuous-time derivative of a typed analog expression";
  let arguments = (ins
    AnyType:$input,
    OptionalAttr<StrAttr>:$operator_contract,
    OptionalAttr<StrAttr>:$operator_id,
    OptionalAttr<StrAttr>:$owner,
    OptionalAttr<StrAttr>:$context,
    OptionalAttr<StrAttr>:$input_dimension,
    OptionalAttr<StrAttr>:$result_dimension,
    OptionalAttr<StrAttr>:$initialization,
    OptionalAttr<ArrayAttr>:$analyses,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogIdtOp : Nodal_Op<"analog_idt", [HasParent<"AnalogOp">]> {
  let summary = "State-owning continuous-time integral of a typed analog expression";
  let arguments = (ins
    Variadic<AnyType>:$inputs,
    OptionalAttr<StrAttr>:$operator_contract,
    OptionalAttr<StrAttr>:$operator_id,
    OptionalAttr<StrAttr>:$owner,
    OptionalAttr<StrAttr>:$context,
    OptionalAttr<StrAttr>:$input_dimension,
    OptionalAttr<StrAttr>:$result_dimension,
    OptionalAttr<StrAttr>:$state_id,
    OptionalAttr<StrAttr>:$initialization,
    OptionalAttr<StrAttr>:$initial_dimension,
    OptionalAttr<ArrayAttr>:$analyses,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

// No Pure trait: history, time observation, and step-bound effects must survive DCE.
def Nodal_AnalogTransitionOp : Nodal_Op<"analog_transition", [HasParent<"AnalogOp">]> {
  let summary = "Source-semantic Increment 36 analog_transition operator";
  let arguments = (ins
    Variadic<AnyType>:$inputs,
    OptionalAttr<StrAttr>:$operator_contract,
    OptionalAttr<StrAttr>:$operator_id,
    OptionalAttr<StrAttr>:$owner,
    OptionalAttr<StrAttr>:$context,
    OptionalAttr<ArrayAttr>:$operand_dimensions,
    OptionalAttr<StrAttr>:$result_dimension,
    OptionalAttr<StrAttr>:$input_continuity,
    OptionalAttr<StrAttr>:$output_continuity,
    OptionalAttr<StrAttr>:$state_id,
    OptionalAttr<ArrayAttr>:$analyses,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

// No Pure trait: history, time observation, and step-bound effects must survive DCE.
def Nodal_AnalogSlewOp : Nodal_Op<"analog_slew", [HasParent<"AnalogOp">]> {
  let summary = "Source-semantic Increment 36 analog_slew operator";
  let arguments = (ins
    Variadic<AnyType>:$inputs,
    OptionalAttr<StrAttr>:$operator_contract,
    OptionalAttr<StrAttr>:$operator_id,
    OptionalAttr<StrAttr>:$owner,
    OptionalAttr<StrAttr>:$context,
    OptionalAttr<ArrayAttr>:$operand_dimensions,
    OptionalAttr<StrAttr>:$result_dimension,
    OptionalAttr<StrAttr>:$input_continuity,
    OptionalAttr<StrAttr>:$output_continuity,
    OptionalAttr<StrAttr>:$state_id,
    OptionalAttr<ArrayAttr>:$analyses,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

// No Pure trait: history, time observation, and step-bound effects must survive DCE.
def Nodal_AnalogAbsdelayOp : Nodal_Op<"analog_absdelay", [HasParent<"AnalogOp">]> {
  let summary = "Source-semantic Increment 36 analog_absdelay operator";
  let arguments = (ins
    Variadic<AnyType>:$inputs,
    OptionalAttr<StrAttr>:$operator_contract,
    OptionalAttr<StrAttr>:$operator_id,
    OptionalAttr<StrAttr>:$owner,
    OptionalAttr<StrAttr>:$context,
    OptionalAttr<ArrayAttr>:$operand_dimensions,
    OptionalAttr<StrAttr>:$result_dimension,
    OptionalAttr<StrAttr>:$input_continuity,
    OptionalAttr<StrAttr>:$output_continuity,
    OptionalAttr<StrAttr>:$state_id,
    OptionalAttr<ArrayAttr>:$analyses,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

// No Pure trait: history, time observation, and step-bound effects must survive DCE.
def Nodal_AnalogAbstimeOp : Nodal_Op<"analog_abstime", [HasParent<"AnalogOp">]> {
  let summary = "Source-semantic Increment 36 analog_abstime operator";
  let arguments = (ins
    Variadic<AnyType>:$inputs,
    OptionalAttr<StrAttr>:$operator_contract,
    OptionalAttr<StrAttr>:$operator_id,
    OptionalAttr<StrAttr>:$owner,
    OptionalAttr<StrAttr>:$context,
    OptionalAttr<ArrayAttr>:$operand_dimensions,
    OptionalAttr<StrAttr>:$result_dimension,
    OptionalAttr<StrAttr>:$input_continuity,
    OptionalAttr<StrAttr>:$output_continuity,
    OptionalAttr<StrAttr>:$state_id,
    OptionalAttr<ArrayAttr>:$analyses,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

// No Pure trait: history, time observation, and step-bound effects must survive DCE.
def Nodal_AnalogBoundStepOp : Nodal_Op<"analog_bound_step", [HasParent<"AnalogOp">]> {
  let summary = "Source-semantic Increment 36 analog_bound_step operator";
  let arguments = (ins
    Variadic<AnyType>:$inputs,
    OptionalAttr<StrAttr>:$operator_contract,
    OptionalAttr<StrAttr>:$operator_id,
    OptionalAttr<StrAttr>:$owner,
    OptionalAttr<StrAttr>:$context,
    OptionalAttr<ArrayAttr>:$operand_dimensions,
    OptionalAttr<StrAttr>:$result_dimension,
    OptionalAttr<StrAttr>:$input_continuity,
    OptionalAttr<StrAttr>:$output_continuity,
    OptionalAttr<StrAttr>:$state_id,
    OptionalAttr<ArrayAttr>:$analyses,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_ContributeOp : Nodal_Op<"contribute", [HasParent<"AnalogOp">]> {
  let summary = "Potential or flow contribution to a conservative branch";
  let arguments = (ins
    Nodal_BranchType:$branch,
    AnyType:$value,
    StrAttr:$kind,
    OptionalAttr<StrAttr>:$generated_owner,
    OptionalAttr<StrAttr>:$generated_induction,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Domain crossings and timing provenance
//===----------------------------------------------------------------------===//

def Nodal_CrossingOp : Nodal_Op<"crossing"> {
  let summary = "Typed explicit CDC, RDC, or mixed-domain crossing";
  let arguments = (ins
    AnyType:$input,
    FlatSymbolRefAttr:$source_domain,
    FlatSymbolRefAttr:$destination_domain,
    StrAttr:$kind,
    OptionalAttr<StrAttr>:$contract,
    OptionalAttr<I64Attr>:$stages,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_StateOwnerOp : Nodal_Op<"state_owner",
    [HasParent<"ModuleOp">]> {
  let summary = "Explicit state ownership by one clock/reset domain";
  let arguments = (ins
    SymbolRefAttr:$state,
    FlatSymbolRefAttr:$domain,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_TimingProvenanceOp : Nodal_Op<"timing_provenance",
    [HasParent<"ModuleOp">]> {
  let summary = "Target-neutral timing and relationship provenance";
  let arguments = (ins
    SymbolRefAttr:$owner,
    StrAttr:$relationship,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Semantic enums and FSM/statechart graph
//===----------------------------------------------------------------------===//

def Nodal_EnumOp : Nodal_Op<"enum",
    [IsolatedFromAbove, Symbol, SymbolTable, NoTerminator]> {
  let summary = "Semantic enum definition with canonical interface encoding";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    TypeAttr:$underlying_type,
    StrAttr:$encoding,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_EnumCaseOp : Nodal_Op<"enum_case",
    [HasParent<"EnumOp">, Symbol]> {
  let summary = "Named semantic enum case with an explicit ABI value";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    APIntAttr:$value,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

// F55: static choices retain both authored alternatives and all real SSA edges.
def Nodal_StaticCompareOp : Nodal_Op<"static_compare"> {
  let summary = "Closed comparison of owned static Integer expressions";
  let arguments = (ins I64:$lhs, I64:$rhs, StrAttr:$comparison, DictionaryAttr:$metadata);
  let results = (outs Nodal_StaticPredicateType:$result);
  let hasVerifier = 1;
}
def Nodal_StaticLogicOp : Nodal_Op<"static_logic"> {
  let summary = "Closed bounded composition of actual static predicates";
  let arguments = (ins Variadic<Nodal_StaticPredicateType>:$inputs,
    StrAttr:$operator_name, DictionaryAttr:$metadata);
  let results = (outs Nodal_StaticPredicateType:$result);
  let hasVerifier = 1;
}
def Nodal_StructuralIfOp : Nodal_Op<"structural_if",
    [IsolatedFromAbove, SingleBlock, AttrSizedOperandSegments]> {
  let summary = "Isolated static choice with separate captures and exact read-only yields";
  let arguments = (ins Nodal_StaticPredicateType:$predicate,
    Variadic<AnyType>:$true_captures, Variadic<AnyType>:$false_captures,
    StrAttr:$contract_version, StrAttr:$region_id, DictionaryAttr:$metadata);
  let results = (outs Optional<AnyType>:$result);
  let regions = (region SizedRegion<1>:$true_body, SizedRegion<1>:$false_body);
  let hasVerifier = 1;
}
def Nodal_StructuralYieldOp : Nodal_Op<"structural_yield",
    [HasParent<"StructuralIfOp">, Terminator]> {
  let summary = "Zero or one exact read-only branch result";
  let arguments = (ins Optional<AnyType>:$value, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

// F55: typed pure collection templates; no lane expansion or target execution.
def Nodal_CollectionOp : Nodal_Op<"collection",
    [IsolatedFromAbove, SingleBlock, AttrSizedOperandSegments]> {
  let summary = "Isolated typed digital collection callback region";
  let description = [{
    kind is map, zip_with, fold_left or scan_left. Inputs are actual Vec values;
    fold/scan have one seed; captures are explicit values with corresponding
    block arguments. map args are element then captures; zip args are A, B,
    then captures; fold/scan args are accumulator, element, then captures.
    map/zip preserve every input dimension and yield one whole result element.
    fold/scan yield exactly the seed type. scan excludes the seed and preserves
    the input shape and cardinality, in canonical row-major traversal order.
    One isolated block terminates with collection_yield. Region shape and
    finite type proofs never substitute defaults or expand physical lanes.
  }];
  let arguments = (ins Variadic<AnyType>:$inputs, Optional<AnyType>:$seed,
    Variadic<AnyType>:$captures, StrAttr:$kind, StrAttr:$region_id,
    DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_CollectionYieldOp : Nodal_Op<"collection_yield",
    [HasParent<"CollectionOp">, Terminator]> {
  let summary = "One whole typed collection callback result";
  let arguments = (ins AnyType:$value, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

def Nodal_DigitalExprOp : Nodal_Op<"digital_expr"> {
  let summary = "Closed typed hardware digital expression";
  let description = [{
    One exact typed result and opcode-specific owned operands. Numeric add/sub
    use max operand width plus one; sub and neg produce SInt, neg grows one bit,
    and multiplication adds widths. Numeric operands have the same UInt/SInt
    kind. Comparisons produce Bits<1>; Bits equality and bitwise operations
    require exact equal widths. concat places its left operand high and produces
    Bits of summed width. select requires a Bits<1> predicate and identical
    complete digital storage branch/result types. Shifts take a UInt literal
    amount: shl grows width, shr preserves width with arithmetic SInt semantics,
    and logical_shr zero-fills. extract takes UInt literal lower-inclusive and
    upper-exclusive bounds and returns Bits of their difference. bool_and/or/not
    require Bits<1>. Division, remainder and dynamic shifts are unsupported.
    digital_alias retains one exact complete digital input type and one typed
    named/keep intent record. Its metadata is a target-neutral semantic boundary,
    not an emitted wire or a reassociation/duplication permission.
    Closed unary
    digital_extend/truncate/wrap/saturate/resize_checked operations retain kind;
    truncation keeps low bits, wrapping is modulo 2^width, and saturation clamps
    to the result range. Checked resize requires all-value or exact-constant
    proof, with no implicit runtime assertion. digital_to_signed adds one bit
    numerically; digital_to_unsigned preserves width and proves nonnegativity.
    digital_reinterpret_signed/unsigned preserve exact bit width and encoding.
    digital_to_real accepts UInt and produces f64 using nearest-ties-even
    binary64 rounding, with an independent finite-result proof. Symbolic widths
    retain exact owned structural identity rather than an overridable default.
    digital_struct_get retains one actual Struct operand and a checked nominal
    schema, field ordinal and name. Its result is the receiver's exact field type.
    digital_struct_make retains one operand per ordered result field. Schema and
    result must have the same independently verified width family; every operand
    has the exact corresponding result field type. Width families never relax
    storage or assignment equality.
  }];
  let arguments = (ins Variadic<AnyType>:$inputs, StrAttr:$operator_name,
    DictionaryAttr:$metadata, OptionalAttr<TypeAttr>:$struct_schema,
    OptionalAttr<I64Attr>:$field_ordinal, OptionalAttr<StrAttr>:$field_name);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_EnumExprOp : Nodal_Op<"enum_expr"> {
  let summary = "Closed typed hardware enum value operation";
  let description = [{
    The operator_name is enum_literal, enum_decode, enum_is_valid,
    enum_as_bits, enum_eq, enum_is_one_of or enum_select. enum_ref resolves an
    actual root enum symbol; metadata repeats its checked versioned ABI.
    enum_decode takes original Bits and one belonging default case ID. A legal
    code passes through unchanged; an illegal code produces that default. The
    separate enum_is_valid on those original Bits remains false for illegal
    input. enum_as_bits preserves the exact encoding. enum_eq compares codes
    of the identical semantic enum. enum_is_one_of with no case IDs is false.
    enum_select takes selector, explicit invalid result and one result per legal
    case in declaration order, with case_ids in that same order. Its invalid
    result handles illegal codes; missing legal cases are never a default.
  }];
  let arguments = (ins Variadic<AnyType>:$inputs, StrAttr:$operator_name,
    FlatSymbolRefAttr:$enum_ref, ArrayAttr:$case_ids, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_FsmOp : Nodal_Op<"fsm",
    [HasParent<"ModuleOp">, Symbol, SymbolTable, NoTerminator]> {
  let summary = "Typed FSM/statechart graph owned by one domain";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    TypeAttr:$state_type,
    FlatSymbolRefAttr:$domain,
    StrAttr:$encoding,
    StrAttr:$illegal_policy,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_FsmStateOp : Nodal_Op<"fsm_state",
    [HasParent<"FsmOp">, Symbol, SymbolTable, NoTerminator]> {
  let summary = "Named FSM state with explicit initial and terminal policy";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    BoolAttr:$initial,
    BoolAttr:$terminal,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_FsmTransitionOp : Nodal_Op<"fsm_transition",
    [HasParent<"FsmStateOp">]> {
  let summary = "Explicit FSM transition with deterministic priority";
  let arguments = (ins
    FlatSymbolRefAttr:$destination,
    APIntAttr:$priority,
    StrAttr:$condition,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_FsmActionOp : Nodal_Op<"fsm_action",
    [HasParent<"FsmStateOp">]> {
  let summary = "Entry, active, exit, or transition action descriptor";
  let arguments = (ins
    StrAttr:$phase,
    StrAttr:$effect,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_FsmCompletionOp : Nodal_Op<"fsm_completion",
    [HasParent<"FsmOp">]> {
  let summary = "Typed statechart completion relationship";
  let arguments = (ins
    FlatSymbolRefAttr:$source,
    FlatSymbolRefAttr:$destination,
    StrAttr:$kind,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

#endif // NODAL_DIALECT_NODAL_NODALOPS_TD

// F56: local executable sequential boundary; F19 descriptors remain distinct.
def Nodal_LocalDomainBindingOp : Nodal_Op<"local_domain_binding", [HasParent<"ModuleOp">]> {
  let summary = "Physical typed clock and optional reset for a local domain";
  let arguments = (ins AnyType:$clock, Variadic<AnyType>:$reset,
    FlatSymbolRefAttr:$domain, StrAttr:$reset_polarity, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}
def Nodal_BehavioralPredicateOp : Nodal_Op<"behavioral_predicate"> {
  let summary = "Checked static predicate as a behavioral Bool value";
  let arguments = (ins Nodal_StaticPredicateType:$predicate, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}
def Nodal_BehavioralIfOp : Nodal_Op<"behavioral_if",
    [IsolatedFromAbove, SingleBlock, NoTerminator, AttrSizedOperandSegments]> {
  let summary = "Owned behavioral priority choice with explicit captures";
  let arguments = (ins AnyType:$predicate,
    Variadic<AnyType>:$true_captures, Variadic<AnyType>:$false_captures,
    StrAttr:$contract_version, StrAttr:$region_id, DictionaryAttr:$metadata);
  let regions = (region SizedRegion<1>:$true_body, SizedRegion<1>:$false_body);
  let hasVerifier = 1;
}
def Nodal_SequentialSelectOp : Nodal_Op<"sequential_select", [HasParent<"ModuleOp">]> {
  let summary = "Exact typed priority selection with explicit hold alternatives";
  let arguments = (ins AnyType:$predicate, AnyType:$true_value, AnyType:$false_value,
    DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}
def Nodal_SequentialUpdateOp : Nodal_Op<"sequential_update", [HasParent<"ModuleOp">]> {
  let summary = "Normalized clocked next state; reset dominates next-state enable";
  let arguments = (ins AnyType:$target, AnyType:$next, AnyType:$clock,
    Variadic<AnyType>:$reset_and_initial, FlatSymbolRefAttr:$domain,
    StrAttr:$edge, StrAttr:$reset_policy, StrAttr:$reset_polarity,
    StrAttr:$root_id, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}

// F56 v0.2: executable closed action ingress; F19 descriptors remain unchanged.
def Nodal_FlatFSMOp : Nodal_Op<"flat_fsm", [HasParent<"ModuleOp">,
    IsolatedFromAbove, NoTerminator]> {
  let summary = "Checked flat state graph with actual guard and action edges";
  let arguments = (ins Variadic<AnyType>:$inputs, StrAttr:$contract_version,
    StrAttr:$machine_id, FlatSymbolRefAttr:$enum_ref, FlatSymbolRefAttr:$domain,
    StrAttr:$initial_case, StrAttr:$requested_encoding, StrAttr:$resolved_encoding,
    I64Attr:$local_width, ArrayAttr:$case_ids, ArrayAttr:$local_codes,
    StrAttr:$illegal_policy, ArrayAttr:$states, ArrayAttr:$transitions,
    ArrayAttr:$actions, DenseI32ArrayAttr:$action_capture_counts, DictionaryAttr:$metadata);
  let results = (outs AnyType:$next, AnyType:$taken);
  let regions = (region VariadicRegion<AnyRegion>:$action_bodies);
  let hasVerifier = 1;
}
def Nodal_EnumSwitchOp : Nodal_Op<"enum_switch", [IsolatedFromAbove, NoTerminator]> {
  let summary = "Exact exhaustive enum action choice with explicit invalid arm";
  let arguments = (ins Variadic<AnyType>:$inputs, StrAttr:$contract_version,
    StrAttr:$region_id, FlatSymbolRefAttr:$enum_ref, ArrayAttr:$case_ids,
    DenseI32ArrayAttr:$capture_counts, ArrayAttr:$arm_ids, DictionaryAttr:$metadata);
  let regions = (region VariadicRegion<AnyRegion>:$arms);
  let hasVerifier = 1;
}
def Nodal_ResetAssertedOp : Nodal_Op<"reset_asserted", [HasParent<"ModuleOp">]> {
  let summary = "Actual bound reset capability and polarity as a Boolean";
  let arguments = (ins AnyType:$reset, FlatSymbolRefAttr:$domain,
    StrAttr:$reset_polarity, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}
def Nodal_SequentialAssertOp : Nodal_Op<"sequential_assert", [HasParent<"ModuleOp">]> {
  let summary = "Actual clock sampled property with exact owning domain";
  let arguments = (ins AnyType:$property, AnyType:$clock, FlatSymbolRefAttr:$domain,
    StrAttr:$edge, StrAttr:$root_id, DictionaryAttr:$metadata);
  let hasVerifier = 1;
}
