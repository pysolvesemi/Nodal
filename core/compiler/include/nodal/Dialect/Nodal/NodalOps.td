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

def Nodal_InstanceOp : Nodal_Op<"instance",
    [HasParent<"ModuleOp">, Symbol]> {
  let summary = "Typed module instance with explicit bindings";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    FlatSymbolRefAttr:$module,
    DictionaryAttr:$parameter_bindings,
    DictionaryAttr:$domain_bindings,
    DictionaryAttr:$metadata
  );
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
    [HasParent<"ModuleOp">]> {
  let summary = "Lossless typed override of an instantiated module parameter";
  let arguments = (ins
    AnyType:$value,
    FlatSymbolRefAttr:$instance,
    FlatSymbolRefAttr:$parameter,
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
    Variadic<Index>:$indices,
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
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_GenerateOp : Nodal_Op<"generate", [NoTerminator]> {
  let summary = "Symbolic structural generation region";
  let arguments = (ins
    StrAttr:$induction,
    AnyAttr:$lower,
    AnyAttr:$upper,
    AnyAttr:$step,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_HardwareLoopOp : Nodal_Op<"hardware_loop", [NoTerminator]> {
  let summary = "Bounded hardware-iteration region with declared effects";
  let arguments = (ins
    StrAttr:$induction,
    AnyAttr:$lower,
    AnyAttr:$upper,
    AnyAttr:$step,
    StrAttr:$effect_policy,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
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
    I1:$enable,
    StrAttr:$mode,
    DictionaryAttr:$metadata
  );
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

def Nodal_NodeOp : Nodal_Op<"node", [HasParent<"ModuleOp">]> {
  let summary = "Internal conservative node";
  let arguments = (ins
    StrAttr:$name,
    OptionalAttr<StrAttr>:$source_path,
    DictionaryAttr:$metadata
  );
  let results = (outs Nodal_TerminalType:$result);
  let hasVerifier = 1;
}

def Nodal_ConnectOp : Nodal_Op<"connect", [HasParent<"ModuleOp">]> {
  let summary = "Source-semantic conservative connection relation";
  let arguments = (ins
    Variadic<Nodal_TerminalType>:$members,
    StrAttr:$connection_id,
    StrAttr:$source_path,
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

def Nodal_ProbeOp : Nodal_Op<"probe", [HasParent<"ModuleOp">]> {
  let summary = "Compiler-owned source-free potential or flow probe intent";
  let arguments = (ins
    Variadic<AnyType>:$subject,
    StrAttr:$form,
    StrAttr:$kind,
    StrAttr:$constraint_intent,
    ArrayAttr:$provenance,
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

def Nodal_AnalogOp : Nodal_Op<"analog", [HasParent<"ModuleOp">, NoTerminator]> {
  let summary = "Continuous-time analog equation region";
  let arguments = (ins DictionaryAttr:$metadata);
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogProcedureOp
    : Nodal_Op<"analog_procedure", [HasParent<"AnalogOp">, NoTerminator]> {
  let summary = "Ordered component-local analog procedural region";
  let arguments = (ins StrAttr:$owner, DictionaryAttr:$metadata);
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

// No Pure trait: an unused monitor must retain timestep/history effects.
class Nodal_AnalogEventOp<string mnemonic> : Nodal_Op<mnemonic> {
  let summary = "Source-semantic analog event monitor or analysis lifecycle event";
  let arguments = (ins StrAttr:$event_id, StrAttr:$owner, StrAttr:$contract,
    StrAttr:$name, ArrayAttr:$arguments, ArrayAttr:$analyses,
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
    DictionaryAttr:$metadata);
  let results = (outs Nodal_AnalogEventType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogOnOp : Nodal_Op<"analog_on", [NoTerminator]> {
  let summary = "Nonblocking analog event-controlled statement region";
  let arguments = (ins Nodal_AnalogEventType:$event,
    StrAttr:$statement_id, StrAttr:$owner, DictionaryAttr:$metadata);
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
    StrAttr:$identity,
    StrAttr:$owner,
    I64Attr:$declaration_order,
    BoolAttr:$initialized,
    StrAttr:$initializer_value,
    StrAttr:$initializer_kind,
    StrAttr:$initializer_dimension,
    ArrayAttr:$initializer_reads,
    DictionaryAttr:$metadata
  );
  let results = (outs Nodal_VariableType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogVariableReadOp : Nodal_Op<"analog_variable_read"> {
  let summary = "Read the current value of one analog procedural variable";
  let arguments = (ins
    Nodal_VariableType:$variable,
    StrAttr:$read_id,
    StrAttr:$owner,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogAssignOp : Nodal_Op<"analog_assign"> {
  let summary = "Authored ordered assignment to one analog procedural variable";
  let arguments = (ins
    Nodal_VariableType:$variable,
    Variadic<AnyType>:$values,
    StrAttr:$statement_id,
    I64Attr:$authored_order,
    StrAttr:$owner,
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

def Nodal_RealLiteralOp : Nodal_Op<"real_literal", [HasParent<"AnalogOp">]> {
  let summary = "Finite real dimensionless literal in an analog expression";
  let arguments = (ins F64Attr:$value, DictionaryAttr:$metadata);
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
