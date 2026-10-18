#include "nodal/Backend/AnalogEventBackend.h"

#include "llvm/Support/raw_ostream.h"

#include <initializer_list>
#include <optional>
#include <string>

int main() {
  using nodal::AnalogEventSymbolKind;
  nodal::AnalogEventSymbols symbols;
  symbols.try_emplace("lanes", nodal::AnalogEventSymbol{AnalogEventSymbolKind::Integer, false,
                                                        std::nullopt, std::nullopt});
  symbols.try_emplace("gain", nodal::AnalogEventSymbol{AnalogEventSymbolKind::Real, false,
                                                       std::nullopt, std::nullopt});
  symbols.try_emplace("p", nodal::AnalogEventSymbol{AnalogEventSymbolKind::Terminal, false,
                                                    std::nullopt, std::nullopt});
  symbols.try_emplace("pins",
                      nodal::AnalogEventSymbol{AnalogEventSymbolKind::TerminalArray, false, 0, 3});
  unsigned count = 0;
  for (llvm::StringRef declaration :
       {"real data[0:3];", "real symbolic[0:(lanes - 1)];", "real scalar;", "integer counter;",
        "integer other;", "genvar occurrence;", "real initializedScalar = (gain * 2.0);",
        "integer initializedCondition = (gain > 0.0 ? 1 : 2);",
        "integer initializedMinimum = -2147483648;",
        "integer initializedPrecedence = 2147483647 + 1 * 0;", "real singleton[0:0];",
        "real maximum[0:(2147483647 - 1)];", "real precedence[0:2 + 3 * 4];"}) {
    ++count;
    if (mlir::failed(nodal::reparseAnalogEventDeclaration(declaration, symbols))) {
      llvm::errs() << "rejected valid event declaration: " << declaration << '\n';
      return 1;
    }
  }
  if (symbols.lookup("data").lowerBound != 0 || symbols.lookup("data").upperBound != 3 ||
      symbols.lookup("symbolic").lowerBound != 0 || symbols.lookup("symbolic").upperBound ||
      symbols.lookup("precedence").upperBound != 14 ||
      symbols.lookup("scalar").kind != AnalogEventSymbolKind::Real ||
      symbols.lookup("counter").kind != AnalogEventSymbolKind::Integer)
    return 1;

  for (llvm::StringRef declaration : {"real data[0:3];",
                                      "real bad[1:3];",
                                      "real bad[0:-1];",
                                      "integer bad[0:3];",
                                      "genvar bad[0:3];",
                                      "real bad[0:2147483647];",
                                      "real bad[0:2147483648];",
                                      "real bad[0:2.5];",
                                      "real bad[0:3e0];",
                                      "real bad[0:(lanes / 0)];",
                                      "real bad[0:(lanes % 0)];",
                                      "real bad[0:(2147483647 + 1)];",
                                      "real bad[0:(65536 * 65536)];",
                                      "real bad[0:((2147483647 + 1) - 1)];",
                                      "real bad[0:(-2147483648 / -1)];",
                                      "real bad[0:(-2147483648 % -1)];",
                                      "real bad[0:(lanes -)];",
                                      "real bad[0:missing];",
                                      "real bad[0:gain];",
                                      "real bad[0:data];",
                                      "real bad[0:counter[0]];",
                                      "real bad[0:sqrt(4)];",
                                      "real bad[0:3] = 0.0;",
                                      "real bad[0:3][0:1];",
                                      "real bad[0:3:1];",
                                      "real bad[0:3;",
                                      "real bad[0:3]];",
                                      "real bad[];",
                                      "real bad = missing;",
                                      "real bad = bad;",
                                      "real bad = data;",
                                      "real bad = data[4];",
                                      "real bad = scalar[0];",
                                      "real bad = 1.0; trailing",
                                      "real bad, other;",
                                      "real bad = 1e999;",
                                      "integer bad = (2147483647 + 1);",
                                      "integer bad = (1 / 0);",
                                      "integer bad = (65536 * 65536);",
                                      "integer bad = ((2147483647 + 1) > 0);",
                                      "integer bad = (gain > 0.0 ? (1 / 0) : 0);",
                                      "real input;"}) {
    ++count;
    const auto size = symbols.size();
    if (mlir::succeeded(nodal::reparseAnalogEventDeclaration(declaration, symbols)) ||
        symbols.size() != size || symbols.contains("bad")) {
      llvm::errs() << "accepted invalid event declaration or polluted symbols: " << declaration
                   << '\n';
      return 1;
    }
  }

  auto validBlock = [&](llvm::StringRef source, const nodal::AnalogEventSymbols *environment) {
    auto result = nodal::reparseAnalogEventBlock(source, environment);
    return mlir::succeeded(result) && *result == source.size();
  };
  for (llvm::StringRef block :
       {"begin data[0] = 1.0; scalar = data[3]; end",
        "begin scalar = (data[0] + data[1]); data[2] = scalar; end",
        "begin data[2 + 1] = data[3 * 1]; end",
        "begin singleton[0] = 1; precedence[14] = singleton[0]; end",
        "begin maximum[2147483646] = 1; end",
        "begin symbolic[lanes - 1] = data[(counter / 2) % 4]; end",
        "begin data[(counter % 2) * 2 + 1] = symbolic[counter]; end",
        "begin other = (((counter / 2) * 3) + ((counter % 2) + 1)); "
        "symbolic[other] = data[counter]; end",
        "begin other = (scalar > gain); other = !other; "
        "counter = (other ? 1 : 2); end",
        "begin other = ((scalar > gain) && (sqrt(data[0]) <= gain)); "
        "counter = (other ? counter + 1 : lanes - 1); end",
        "begin counter = -2147483648; counter = 2147483647 + 1 * 0; "
        "other = gain > 2 + 3 * 4; counter = 1 / (2 == 2); end",
        "begin : event_procedure @(initial_step) begin "
        "for (counter = 0; counter < lanes; counter = counter + 1) begin "
        "symbolic[counter] = scalar; end end end",
        "begin for (counter = 0; (counter < lanes) && !other; counter = counter + 1) "
        "begin scalar = symbolic[counter]; end end",
        "begin if (scalar > gain) begin data[0] = scalar; end "
        "else begin data[1] = scalar; end end",
        "begin case (counter) 0, 1: begin data[0] = 1; end "
        "default: begin data[1] = 2; end endcase end",
        "begin scalar = V(p); data[0] = I(pins[counter], p); end",
        "begin @(cross(V(p), 1)) begin scalar = sqrt(data[0]); end end",
        "begin scalar = (analysis(\"tran\") ? data[0] : data[1]); end",
        "begin $strobe(\"missing[999] ; @(() [ ]\"); $finish; end"}) {
    ++count;
    if (!validBlock(block, &symbols)) {
      llvm::errs() << "rejected valid event block: " << block << '\n';
      return 1;
    }
  }
  for (llvm::StringRef block :
       {"begin missing = 1; end",
        "begin scalar = missing; end",
        "begin gain = 1; end",
        "begin lanes = 1; end",
        "begin data = 1; end",
        "begin scalar = data; end",
        "begin scalar[0] = 1; end",
        "begin scalar = scalar[0]; end",
        "begin missing[0] = 1; end",
        "begin scalar = missing[0]; end",
        "begin data[-1] = 1; end",
        "begin scalar = data[-1]; end",
        "begin data[4] = 1; end",
        "begin scalar = data[4]; end",
        "begin data[1 + 3] = 1; end",
        "begin scalar = data[8 / 2]; end",
        "begin singleton[1] = 1; end",
        "begin precedence[15] = 1; end",
        "begin data[2147483648] = 1; end",
        "begin scalar = data[65536 * 65536]; end",
        "begin data[(-2147483648 / -1)] = 1; end",
        "begin scalar = data[(-2147483648 % -1)]; end",
        "begin symbolic[-1] = 1; end",
        "begin scalar = symbolic[0 - 1]; end",
        "begin data[0.0] = 1; end",
        "begin scalar = data[1e0]; end",
        "begin data[gain] = 1; end",
        "begin scalar = data[scalar]; end",
        "begin data[missing] = 1; end",
        "begin scalar = data[data[0]]; end",
        "begin data[counter / 0] = 1; end",
        "begin scalar = data[counter % 0]; end",
        "begin data[0:1] = 1; end",
        "begin scalar = data[]; end",
        "begin data[0][1] = 1; end",
        "begin scalar = data[0][1]; end",
        "begin data[(counter +)] = 1; end",
        "begin scalar = data[0; end",
        "begin data[0] += 1; end",
        "begin scalar = data[0]]; end",
        "begin scalar = data[0] % 2; end",
        "begin scalar = 2.5 % 2; end",
        "begin scalar = (((counter / 2) * 3) + ((counter % 2) + 1)); end",
        "begin other = (counter % gain); end",
        "begin other = (scalar % 2); end",
        "begin other = (counter % missing); end",
        "begin other = (counter % 0); end",
        "begin other = ((2147483647 + 1) % 2); end",
        "begin counter = (2147483647 + 1); end",
        "begin counter = (1 / 0); end",
        "begin counter = (65536 * 65536); end",
        "begin counter = (-2147483648 / -1); end",
        "begin counter = -(-2147483648); end",
        "begin other = ((2147483647 + 1) > 0); end",
        "begin other = (gain > (2147483647 + 1)); end",
        "begin other = gain > 2147483647 + 1; end",
        "begin other = (gain > (65536 * 65536)); end",
        "begin other = (other && (1 / 0)); end",
        "begin other = (other || (2147483647 + 1)); end",
        "begin other = !(1 / 0); end",
        "begin other = (counter ? 1 / 0 : 0); end",
        "begin other = (counter ? 0 : 2147483647 + 1); end",
        "begin other = (1 ? 0 : 65536 * 65536); end",
        "begin other = (gain > 0.0 ? 0 : 1 / 0); end",
        "begin other = (sqrt(2147483647 + 1) > gain); end",
        "begin counter = 1 / (1 == 0); end",
        "begin counter = 1 / (1 ? 0 : 1); end",
        "begin other = (gain / 0 > 1.0); end",
        "begin other = (gain / 0.0 > 1.0); end",
        "begin for (scalar = 0; scalar < lanes; scalar = scalar + 1) begin end end",
        "begin for (data[0] = 0; counter < lanes; data[0] = counter + 1) begin end end",
        "begin for (counter = 0; counter < lanes; other = other + 1) begin end end",
        "begin for (counter = 0; counter < lanes; counter = counter + 1 begin end end",
        "begin scalar = p; end",
        "begin p = 1; end",
        "begin pins[0] = 1; end",
        "begin scalar = pins[0]; end",
        "begin scalar = V(missing); end",
        "begin scalar = V(data[0]); end",
        "begin scalar = V(scalar); end",
        "begin scalar = V(pins); end",
        "begin scalar = V(pins[4]); end",
        "begin @(initial_step) begin @(final_step) begin end end end",
        "begin scalar = ddt(V(p)); end",
        "begin scalar = idt(V(p)); end",
        "begin scalar = white_noise(1, \"noise\"); end",
        "begin scalar = laplace_nd(V(p), '{1}, '{1}); end",
        "begin scalar = $abstime; end",
        "begin scalar = $random(); end",
        "begin V(p) <+ scalar; end",
        "begin $display(\"text\"); end",
        "begin $strobe(\"unterminated); end",
        "begin $strobe(\"escaped\\\"text\"); end",
        "begin scalar = 1; end trailing"}) {
    ++count;
    if (validBlock(block, &symbols)) {
      llvm::errs() << "accepted invalid event block: " << block << '\n';
      return 1;
    }
  }

  // The caller supplies the visible lexical scope; a completed generated
  // occurrence cannot lend its storage to a sibling or outer event block.
  auto generated = symbols;
  if (mlir::failed(nodal::reparseAnalogEventDeclaration("real generatedLocal[0:1];", generated)) ||
      !validBlock("begin generatedLocal[0] = data[0]; scalar = generatedLocal[1]; end",
                  &generated) ||
      validBlock("begin generatedLocal[0] = data[0]; end", &symbols) ||
      validBlock("begin scalar = generatedLocal[1]; end", &symbols))
    return 1;
  count += 3;

  std::string embeddedNul = "begin scalar = 1;";
  embeddedNul.push_back('\0');
  embeddedNul += " end";
  if (validBlock(embeddedNul, &symbols))
    return 1;
  std::string nulDeclaration = "real bad";
  nulDeclaration.push_back('\0');
  nulDeclaration += ";";
  if (mlir::succeeded(nodal::reparseAnalogEventDeclaration(nulDeclaration, symbols)))
    return 1;
  count += 2;

  // Keep legacy callers and expression grammars from gaining array syntax or
  // event-only effects implicitly when no declaration environment is supplied.
  if (!validBlock("begin unknown = V(p); end", nullptr) ||
      validBlock("begin unknown[0] = 1; end", nullptr) ||
      validBlock("begin unknown = data[0]; end", nullptr) ||
      validBlock("begin unknown = 4 % 3; end", nullptr) ||
      mlir::succeeded(nodal::reparseAnalogTransferCall("laplace_nd(data[0], '{1}, '{1})")) ||
      mlir::succeeded(nodal::reparseAnalogNoiseCall("white_noise(data[0], \"noise\")")))
    return 1;
  count += 6;
  llvm::outs() << "Foundation 43 event-array reparse: " << count << " cases passed\n";
  return 0;
}
