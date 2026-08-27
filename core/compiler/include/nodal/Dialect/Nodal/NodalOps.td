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
  let summary = "Symbolic target-visible module parameter";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    TypeAttr:$type,
    AnyAttr:$default_value,
    StrAttr:$variability,
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

def Nodal_TerminalOp : Nodal_Op<"terminal", [HasParent<"ModuleOp">]> {
  let summary = "Boundary conservative terminal";
  let arguments = (ins StrAttr:$name, DictionaryAttr:$metadata);
  let results = (outs Nodal_TerminalType:$result);
  let hasVerifier = 1;
}

def Nodal_NodeOp : Nodal_Op<"node", [HasParent<"ModuleOp">]> {
  let summary = "Internal conservative node";
  let arguments = (ins StrAttr:$name, DictionaryAttr:$metadata);
  let results = (outs Nodal_TerminalType:$result);
  let hasVerifier = 1;
}

def Nodal_BranchOp : Nodal_Op<"branch"> {
  let summary = "Directed conservative branch between compatible terminals";
  let arguments = (ins
    Nodal_TerminalType:$positive,
    Nodal_TerminalType:$negative,
    DictionaryAttr:$metadata
  );
  let results = (outs Nodal_BranchType:$result);
  let hasVerifier = 1;
}

def Nodal_AccessOp : Nodal_Op<"access"> {
  let summary = "Potential or flow access on a conservative branch";
  let arguments = (ins
    Nodal_BranchType:$branch,
    StrAttr:$kind,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
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

def Nodal_RealLiteralOp : Nodal_Op<"real_literal", [HasParent<"AnalogOp">]> {
  let summary = "Finite real literal in an analog expression";
  let arguments = (ins F64Attr:$value, DictionaryAttr:$metadata);
  let results = (outs F64:$result);
  let hasVerifier = 1;
}

def Nodal_ParameterRefOp : Nodal_Op<"parameter_ref", [HasParent<"AnalogOp">]> {
  let summary = "Typed reference to an enclosing real parameter";
  let arguments = (ins FlatSymbolRefAttr:$parameter, DictionaryAttr:$metadata);
  let results = (outs F64:$result);
  let hasVerifier = 1;
}

class Nodal_AnalogBinaryOp<string mnemonic, string description>
    : Nodal_Op<mnemonic, [HasParent<"AnalogOp">]> {
  let summary = description;
  let arguments = (ins F64:$lhs, F64:$rhs, DictionaryAttr:$metadata);
  let results = (outs F64:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogAddOp : Nodal_AnalogBinaryOp<"analog_add", "Analog real addition">;
def Nodal_AnalogSubOp : Nodal_AnalogBinaryOp<"analog_sub", "Analog real subtraction">;
def Nodal_AnalogMulOp : Nodal_AnalogBinaryOp<"analog_mul", "Analog real multiplication">;
def Nodal_AnalogDivOp : Nodal_AnalogBinaryOp<"analog_div", "Analog real division">;

def Nodal_AnalogDdtOp : Nodal_Op<"analog_ddt", [HasParent<"AnalogOp">]> {
  let summary = "Continuous-time derivative of a real analog expression";
  let arguments = (ins F64:$input, DictionaryAttr:$metadata);
  let results = (outs F64:$result);
  let hasVerifier = 1;
}

def Nodal_ContributeOp : Nodal_Op<"contribute", [HasParent<"AnalogOp">]> {
  let summary = "Potential or flow contribution to a conservative branch";
  let arguments = (ins
    Nodal_BranchType:$branch,
    F64:$value,
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
