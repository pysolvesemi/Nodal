//===- NodalTypes.td - Nodal type definitions ---------------*- tablegen -*-===//
//
// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
//
//===----------------------------------------------------------------------===//

#ifndef NODAL_DIALECT_NODAL_NODALTYPES_TD
#define NODAL_DIALECT_NODAL_NODALTYPES_TD

include "mlir/IR/AttrTypeBase.td"
include "nodal/Dialect/Nodal/NodalDialect.td"

class Nodal_Type<string name, string typeMnemonic>
    : TypeDef<Nodal_Dialect, name> {
  let mnemonic = typeMnemonic;
}

def Nodal_StaticPredicateType : Nodal_Type<"StaticPredicate", "static_predicate"> {
  let summary = "Owned elaboration-time predicate token, never runtime storage";
  let assemblyFormat = "";
}

def Nodal_GeneratedFamilyType : Nodal_Type<"GeneratedFamily", "generated_family"> {
  let summary = "Exact generated declaration family capability, never a data value";
  let assemblyFormat = "";
}

def Nodal_BitsType : Nodal_Type<"Bits", "bits"> {
  let summary = "Signless finite-width bit container";
  let parameters = (ins "int64_t":$width);
  let assemblyFormat = "`<` $width `>`";
  let genVerifyDecl = 1;
}

def Nodal_UIntType : Nodal_Type<"UInt", "uint"> {
  let summary = "Unsigned finite-width integer";
  let parameters = (ins "int64_t":$width);
  let assemblyFormat = "`<` $width `>`";
  let genVerifyDecl = 1;
}

def Nodal_SIntType : Nodal_Type<"SInt", "sint"> {
  let summary = "Two's-complement signed finite-width integer";
  let parameters = (ins "int64_t":$width);
  let assemblyFormat = "`<` $width `>`";
  let genVerifyDecl = 1;
}

def Nodal_ParametricIntegerType : Nodal_Type<"ParametricInteger", "digital"> {
  let summary = "Finite-width digital scalar with an owned symbolic width";
  let description = [{
    Kind is bits, uint, or sint. The width token names a structural parameter or
    typed constant expression in the using module. It never contains a default
    substitution or an untyped arithmetic expression. Concrete widths retain
    the Bits, UInt, and SInt representations.
  }];
  let parameters = (ins
    StringRefParameter<"bits, uint, or sint">:$kind,
    StringRefParameter<"owned canonical static width token">:$width
  );
  let assemblyFormat = "`<` $kind `,` $width `>`";
  let genVerifyDecl = 1;
}

def Nodal_ControlCapabilityType : Nodal_Type<"ControlCapability", "capability"> {
  let summary = "Clock/reset boundary capability, distinct from Boolean and storage values";
  let description = [{
    Exact clock or reset identity on typed boundary transport. This carrier does
    not encode timing, execution, CDC/RDC policy or reset behavior. It cannot be
    manufactured by literals, scalar operations, selection or ordinary storage.
  }];
  let parameters = (ins StringRefParameter<"clock or reset">:$kind);
  let assemblyFormat = "`<` $kind `>`";
  let genVerifyDecl = 1;
}

def Nodal_StructType : Nodal_Type<"Struct", "struct"> {
  let summary = "Directionless recursive digital value with ordered named fields";
  let description = [{
    Fields are an ordered ArrayAttr of dictionaries with exactly a name StringAttr
    and a type TypeAttr. Field order, authored names, nested shape, and exact leaf
    types are semantic identity; this representation neither flattens a value nor
    selects storage or a target ABI layout.
  }];
  let parameters = (ins
    StringRefParameter<"authored Struct name">:$name,
    "::mlir::ArrayAttr":$fields
  );
  let assemblyFormat = "`<` $name `,` $fields `>`";
  let genVerifyDecl = 1;
}

def Nodal_QuantityType : Nodal_Type<"Quantity", "quantity"> {
  let summary = "Target-neutral scalar analog quantity";
  let description = [{
    A quantity owns a numeric kind (`integer` or `real`) and a canonical
    physical-dimension signature. Boolean remains ordinary `i1`; target unit
    spelling and scale remain separate from this semantic type.
  }];
  let parameters = (ins
    StringRefParameter<"integer or real">:$kind,
    StringRefParameter<"canonical physical dimension signature">:$dimension
  );
  let assemblyFormat = "`<` $kind `,` $dimension `>`";
  let genVerifyDecl = 1;
}

def Nodal_AnalogEventType : Nodal_Type<"AnalogEvent", "analog_event"> {
  let summary = "Analog event token, distinct from digital edges and numeric values";
  let assemblyFormat = "";
}

def Nodal_AnalogDeferredType : Nodal_Type<"AnalogDeferred", "analog_deferred"> {
  let summary = "Non-evaluating consumer-local Real fill capability";
  let parameters = (ins
    StringRefParameter<"real">:$kind,
    StringRefParameter<"canonical physical dimension signature">:$dimension
  );
  let assemblyFormat = "`<` $kind `,` $dimension `>`";
  let genVerifyDecl = 1;
}

def Nodal_VariableType : Nodal_Type<"Variable", "variable"> {
  let summary = "Typed component-local analog procedural variable handle";
  let description = [{
    The handle retains the source scalar kind and canonical physical dimension.
    It is storage identity, not the current value. Reads are explicit
    `nodal.analog_variable_read` operations and writes are ordered
    `nodal.analog_assign` statements. Unsupported scalar kinds and
    dimensioned Boolean variables are rejected at the compiler boundary with
    `NODAL-ANALOG-033-019`.
  }];
  let parameters = (ins
    StringRefParameter<"integer, real, or boolean">:$kind,
    StringRefParameter<"canonical physical dimension signature">:$dimension
  );
  let assemblyFormat = "`<` $kind `,` $dimension `>`";
  let genVerifyDecl = 1;
}

def Nodal_ShapedType : Nodal_Type<"Shaped", "shaped"> {
  let summary = "Ranked structural value with symbolic-capable dimensions";
  let description = [{
    The dimension string is a canonical comma-separated sequence of positive
    integer or symbolic dimension expressions. It records semantic shape; it
    does not select a target layout or imply memory storage.
  }];
  let parameters = (ins
    StringRefParameter<"canonical comma-separated dimensions">:$dimensions,
    "::mlir::Type":$elementType
  );
  let assemblyFormat = "`<` $dimensions `,` $elementType `>`";
  let genVerifyDecl = 1;
}

def Nodal_InterfaceType : Nodal_Type<"Interface", "interface"> {
  let summary = "Reference to a logical Interface definition";
  let parameters = (ins StringRefParameter<"interface symbol">:$symbol);
  let assemblyFormat = "`<` $symbol `>`";
  let genVerifyDecl = 1;
}

def Nodal_ValidType : Nodal_Type<"Valid", "valid"> {
  let summary = "Valid-only channel carrying one payload type";
  let parameters = (ins "::mlir::Type":$payloadType);
  let assemblyFormat = "`<` $payloadType `>`";
  let genVerifyDecl = 1;
}

def Nodal_StreamType : Nodal_Type<"Stream", "stream"> {
  let summary = "Elastic ready/valid channel carrying one payload type";
  let parameters = (ins "::mlir::Type":$payloadType);
  let assemblyFormat = "`<` $payloadType `>`";
  let genVerifyDecl = 1;
}

def Nodal_ResolvedType : Nodal_Type<"Resolved", "resolved"> {
  let summary = "Digital resolved net with an explicit drive mode";
  let parameters = (ins
    StringRefParameter<"push_pull, open_drain, or open_source">:$driveMode,
    "::mlir::Type":$elementType
  );
  let assemblyFormat = "`<` $driveMode `,` $elementType `>`";
  let genVerifyDecl = 1;
}

def Nodal_DriverType : Nodal_Type<"Driver", "driver"> {
  let summary = "Typed identity of one resolved-net driver";
  let parameters = (ins "::mlir::Type":$elementType);
  let assemblyFormat = "`<` $elementType `>`";
  let genVerifyDecl = 1;
}

def Nodal_TerminalType : Nodal_Type<"Terminal", "terminal"> {
  let summary = "Conservative terminal carrying a discipline identity";
  let parameters = (ins StringRefParameter<"discipline identity">:$discipline);
  let assemblyFormat = "`<` $discipline `>`";
  let genVerifyDecl = 1;
}

def Nodal_BranchType : Nodal_Type<"Branch", "branch"> {
  let summary = "Conservative branch carrying a discipline identity";
  let parameters = (ins StringRefParameter<"discipline identity">:$discipline);
  let assemblyFormat = "`<` $discipline `>`";
  let genVerifyDecl = 1;
}

def Nodal_EnumType : Nodal_Type<"Enum", "enum"> {
  let summary = "Semantic enum identity with a canonical ABI width";
  let parameters = (ins
    StringRefParameter<"enum symbol">:$symbol,
    "int64_t":$width
  );
  let assemblyFormat = "`<` $symbol `,` $width `>`";
  let genVerifyDecl = 1;
}

def Nodal_DomainType : Nodal_Type<"Domain", "domain"> {
  let summary = "Clock/reset domain identity token";
  let parameters = (ins StringRefParameter<"domain symbol">:$symbol);
  let assemblyFormat = "`<` $symbol `>`";
  let genVerifyDecl = 1;
}

#endif // NODAL_DIALECT_NODAL_NODALTYPES_TD
