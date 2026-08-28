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
