#include "nodal/Backend/AnalogEventBackend.h"
#include "nodal/Backend/AnalogUserFunctionBackend.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Backend/Backend.h"

#include "llvm/Support/raw_ostream.h"

#include <initializer_list>
#include <optional>
#include <string>
#include <utility>

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
                                      "real bad = $temperature;",
                                      "real bad = ($simparam(\"tnom\") + 273.15);",
                                      "real bad = $abstime;",
                                      "real bad = sqrt($temperature);",
                                      "real bad = (0 * $temperature);",
                                      "real bad = (1 ? 0.0 : $temperature);",
                                      "integer bad = ($temperature > 273.15);",
                                      "real bad[0:$temperature];",
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
        "begin scalar = $temperature; data[0] = ($simparam(\"tnom\") + 273.15); end",
        "begin scalar = $abstime; end",
        "begin other = ($temperature > ($simparam(\"tnom\") + 273.15)); end",
        "begin @(cross(($temperature - gain), 1)) begin scalar = $temperature; end end",
        "begin if (analysis(\"tran\")) begin scalar = sqrt($temperature * $temperature); end end",
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
        "begin scalar = $temperature(); end",
        "begin scalar = $temperature(1); end",
        "begin scalar = $temperature_extra; end",
        "begin scalar = $simparam(\"tnom\", 27.0); end",
        "begin scalar = $simparam(\"temperature\"); end",
        "begin scalar = $simparam(\"corner\"); end",
        "begin scalar = $simparam(\"TNOM\"); end",
        "begin scalar = $simparam(gain); end",
        "begin scalar = $simparam(); end",
        "begin scalar = $simparam; end",
        "begin scalar = $abstime(); end",
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
      validBlock("begin unknown = $abstime; end", nullptr) ||
      mlir::succeeded(nodal::reparseAnalogTransferCall("laplace_nd(data[0], '{1}, '{1})")) ||
      mlir::succeeded(nodal::reparseAnalogNoiseCall("white_noise(data[0], \"noise\")")))
    return 1;
  count += 7;
  // Declaration replication is a static-only extension. Keep the original
  // 179 declaration/event/legacy cases above independent of these symbols.
  auto initialized = symbols;
  initialized.try_emplace("rows", nodal::AnalogEventSymbol{AnalogEventSymbolKind::Integer, false,
                                                           std::nullopt, std::nullopt});
  initialized.try_emplace("readOnlyArray",
                          nodal::AnalogEventSymbol{AnalogEventSymbolKind::RealArray, false, 0, 3});
  for (llvm::StringRef declaration :
       {"real fixedFill[0:3] = '{4{1.25}};", "real oneFill[0:0] = '{1{-2.5}};",
        "real minimumFill[0:1] = '{2{-2147483648}};",
        "real maximumFill[0:2147483646] = '{2147483647{0.0}};",
        "real arithmeticFill[0:(2 * 3 - 1)] = '{(6 / 2 + 3){gain}};",
        "real symbolicFill[0:(lanes - 1)] = '{lanes{gain}};",
        "real productFill[0:(((lanes + 1) * rows) - 1)] = "
        "'{((lanes + 1) * rows){sqrt(gain * gain)}};",
        "real precedenceFill[0:(lanes + rows * 2 - 1)] = "
        "'{(lanes + rows * 2){(gain + 2.0)}};",
        "real parenthesizedFill[0:((+(lanes)) - 1)] = '{((lanes)){gain}};",
        "real foldedFill[0:((lanes * (1 + 1)) - 1)] = '{(lanes * 2){gain}};",
        "real quotientFill[0:((lanes / 2) - 1)] = '{(lanes / 2){gain}};",
        "real remainderFill[0:((lanes % 3 + 1) - 1)] = '{(lanes % 3 + 1){gain}};",
        "real conditionalFill[0:3] = "
        "'{4{((lanes > 1 && gain > 0.0) ? min(gain, 2.0) : abs(gain))}};",
        "real integerSelectFill[0:3] = '{4{(gain > 0.0 ? lanes + 1 : -1)}};",
        "real realArithmeticFill[0:3] = '{4{(2147483647.0 + 1.0)}};",
        "real checkedFill[0:3] = '{4{(2147483647 + 1 * 0)}};",
        "real comparisonFill[0:3] = '{4{((lanes % 2) == 0 ? 0.0 : 1.0)}};",
        "real booleanFill[0:3] = '{4{(!(lanes == 1) || gain > 0.0 ? 2.0 : 3.0)}};"}) {
    ++count;
    if (mlir::failed(nodal::reparseAnalogEventDeclaration(declaration, initialized))) {
      llvm::errs() << "rejected valid persistent-array declaration: " << declaration << '\n';
      return 1;
    }
  }
  if (initialized.lookup("fixedFill").kind != AnalogEventSymbolKind::RealArray ||
      !initialized.lookup("fixedFill").writable ||
      initialized.lookup("fixedFill").lowerBound != 0 ||
      initialized.lookup("fixedFill").upperBound != 3 ||
      initialized.lookup("maximumFill").upperBound != 2147483646 ||
      initialized.lookup("symbolicFill").upperBound ||
      !validBlock("begin fixedFill[0] = symbolicFill[lanes - 1]; "
                  "scalar = fixedFill[3]; end",
                  &initialized))
    return 1;
  ++count;

  for (llvm::StringRef declaration :
       {"real bad = '{4{0.0}};",
        "integer bad[0:3] = '{4{0.0}};",
        "real bad[0:3] = {4{0.0}};",
        "real bad[0:3] = '{0.0, 0.0, 0.0, 0.0};",
        "real bad[0:3] = '{};",
        "real bad[0:3] = '{{0.0}};",
        "real bad[0:3] = '{4{}};",
        "real bad[0:3] = '{4{0.0, 1.0}};",
        "real bad[0:3] = '{4{'{1{0.0}}}};",
        "real bad[0:3] = '{4{0.0};",
        "real bad[0:3] = '{4{0.0}}};",
        "real bad[0:3] = '{4{0.0}}, other;",
        "real bad[0:3] = '{4{0.0}}; trailing",
        "real bad[0:3] = '{4 + {0.0}};",
        "real bad[0:3] = '{4.0{0.0}};",
        "real bad[0:3] = '{4e0{0.0}};",
        "real bad[0:3] = '{4'h4{0.0}};",
        "real bad[0:3] = '{0{0.0}};",
        "real bad[0:3] = '{-4{0.0}};",
        "real bad[0:3] = '{(2 - 2){0.0}};",
        "real bad[0:3] = '{2147483648{0.0}};",
        "real bad[0:3] = '{((2147483647 + 1) - 2147483644){0.0}};",
        "real bad[0:3] = '{((65536 * 65536) / 1073741824){0.0}};",
        "real bad[0:3] = '{(-2147483648 / -1){0.0}};",
        "real bad[0:3] = '{(-2147483648 % -1){0.0}};",
        "real bad[0:3] = '{(4 / 0){0.0}};",
        "real bad[0:3] = '{(4 % 0){0.0}};",
        "real bad[0:3] = '{3{0.0}};",
        "real bad[0:(lanes - 1)] = '{rows{0.0}};",
        "real bad[0:(lanes - 1)] = '{4{0.0}};",
        "real bad[0:3] = '{lanes{0.0}};",
        "real bad[0:(lanes - 2)] = '{lanes{0.0}};",
        "real bad[0:(lanes - 1)] = '{(lanes + 0){0.0}};",
        "real bad[0:((lanes * 2) - 1)] = '{(2 * lanes){0.0}};",
        "real bad[0:(((lanes + rows) + 1) - 1)] = '{(lanes + (rows + 1)){0.0}};",
        "real bad[0:(counter - 1)] = '{counter{0.0}};",
        "real bad[0:(lanes - 1)] = '{counter{0.0}};",
        "real bad[0:(lanes - 1)] = '{gain{0.0}};",
        "real bad[0:(lanes - 1)] = '{missing{0.0}};",
        "real bad[0:(lanes - 1)] = '{data[0]{0.0}};",
        "real bad[0:3] = '{sqrt(16){0.0}};",
        "real bad[0:3] = '{(1 ? 4 : 2){0.0}};",
        "real bad[0:3] = '{4{bad}};",
        "real bad[0:3] = '{4{missing}};",
        "real bad[0:3] = '{4{scalar}};",
        "real bad[0:3] = '{4{counter}};",
        "real bad[0:3] = '{4{data}};",
        "real bad[0:3] = '{4{data[0]}};",
        "real bad[0:3] = '{4{readOnlyArray[0]}};",
        "real bad[0:3] = '{4{gain[0]}};",
        "real bad[0:3] = '{4{p}};",
        "real bad[0:3] = '{4{V(p)}};",
        "real bad[0:3] = '{4{I(p)}};",
        "real bad[0:3] = '{4{sqrt(V(p))}};",
        "real bad[0:3] = '{4{analysis(\"tran\")}};",
        "real bad[0:3] = '{4{ddt(gain)}};",
        "real bad[0:3] = '{4{white_noise(1, \"noise\")}};",
        "real bad[0:3] = '{4{$abstime}};",
        "real bad[0:3] = '{4{$temperature}};",
        "real bad[0:3] = '{4{($simparam(\"tnom\") + 273.15)}};",
        "real bad[0:3] = '{4{sqrt($temperature)}};",
        "real bad[0:3] = '{4{(0 * $temperature)}};",
        "real bad[0:3] = '{4{(1 ? 0.0 : $temperature)}};",
        "real bad[0:3] = '{4{unknownFunction(gain)}};",
        "real bad[0:3] = '{4{min(scalar, gain)}};",
        "real bad[0:3] = '{4{(gain > 0.0 ? 1.0 : data[0])}};",
        "real bad[0:3] = '{4{2147483648}};",
        "real bad[0:3] = '{4{(2147483647 + 1)}};",
        "real bad[0:3] = '{4{(65536 * 65536)}};",
        "real bad[0:3] = '{4{(gain > (2147483647 + 1) ? 0.0 : 1.0)}};",
        "real bad[0:3] = '{4{sqrt(2147483647 + 1)}};",
        "real bad[0:3] = '{4{(gain > 0.0 ? 0.0 : 1 / 0)}};",
        "real bad[0:3] = '{4{(gain > 0.0 ? 0.0 : -(-2147483648))}};",
        "real bad[0:3] = '{4{(gain / 0.0)}};",
        "real bad[0:3] = '{4{1e999}};"}) {
    ++count;
    const auto size = initialized.size();
    if (mlir::succeeded(nodal::reparseAnalogEventDeclaration(declaration, initialized)) ||
        initialized.size() != size || initialized.contains("bad")) {
      llvm::errs() << "accepted invalid persistent-array declaration or polluted symbols: "
                   << declaration << '\n';
      return 1;
    }
  }

  // The retained structure can be long and flat: construction/comparison must
  // not recursively expand it or copy every accumulated prefix. Distinct
  // parameter names remain distinct, regardless of any equal native defaults.
  std::string longCount = "lanes";
  for (unsigned term = 0; term < 512; ++term)
    longCount += " + rows";
  std::string longDeclaration =
      "real longFill[0:((" + longCount + ") - 1)] = '{(" + longCount + "){gain}};";
  if (mlir::failed(nodal::reparseAnalogEventDeclaration(longDeclaration, initialized)))
    return 1;
  longDeclaration = "real bad[0:((" + longCount + ") - 1)] = '{(" + longCount + " + lanes){gain}};";
  if (mlir::succeeded(nodal::reparseAnalogEventDeclaration(longDeclaration, initialized)) ||
      initialized.contains("bad"))
    return 1;
  count += 2;

  std::string nulPattern = "real bad[0:3] = '{4{0.0";
  nulPattern.push_back('\0');
  nulPattern += "}};";
  if (mlir::succeeded(nodal::reparseAnalogEventDeclaration(nulPattern, initialized)) ||
      initialized.contains("bad") || validBlock("begin scalar = '{4{0.0}}; end", &initialized) ||
      mlir::succeeded(nodal::reparseAnalogTransferCall("laplace_nd(1, '{4{0.0}}, '{1})")) ||
      mlir::succeeded(nodal::reparseAnalogNoiseCall("noise_table('{4{0.0}}, \"noise\")")))
    return 1;
  count += 4;

  // Live selected control/event leaves reuse the same indexed-expression
  // grammar. Coordinate assignments are statements, while numeric reads stay
  // inside the reached predicate, selector, bound or monitor payload.
  for (llvm::StringRef block :
       {"begin counter = 0; other = 3; "
        "if ((data[counter] > gain) && (data[other] < gain)) begin data[0] = 1.0; end "
        "else if ((data[other] > gain) || (data[counter] < 0.0)) begin data[1] = 2.0; end "
        "else begin data[2] = 3.0; end end",
        "begin counter = ((lanes - 1) % 4); "
        "if ((data[counter] > 0.0) ? data[1] > gain : data[2] < gain) "
        "begin data[counter] = gain; end end",
        "begin counter = 0; case (data[counter] > gain) "
        "0: begin data[counter] = 1.0; end 1: begin data[counter] = 2.0; end endcase end",
        "begin counter = 0; other = (data[counter] > 0.0 ? 1 : 2); "
        "for (counter = 0; counter < other; counter = counter + 1) begin "
        "if (data[0] > gain) begin data[0] = data[0] - 1.0; end end end",
        "begin counter = 0; for (other = 0; other < lanes; other = other + 1) begin "
        "if (data[counter] > gain) begin data[counter] = data[counter] - 1.0; end end end",
        "begin counter = 0; other = 3; "
        "@(cross((data[counter] - gain), 1, data[other], 0.01)) begin data[1] = 4.0; end end",
        "begin counter = 0; @(above(data[counter], 0.001, 0.01)) begin end end",
        "begin counter = 0; other = 1; "
        "@(timer(data[counter], data[other], 0.001, 1)) begin data[2] = 1.0; end end",
        "begin counter = 0; other = 1; "
        "@(cross(data[counter], 1) or above(data[other])) begin data[2] = 1.0; end end",
        "begin counter = 0; @(cross(data[counter], 1)) begin end "
        "data[counter] = gain; @(cross(data[counter], 1)) begin end end",
        "begin counter = (lanes - 1); @(above(symbolic[counter])) begin end end",
        "begin counter = 0; other = 3; "
        "@(cross((data[counter] > gain ? data[counter] : data[other]), 0)) begin end end"}) {
    ++count;
    if (!validBlock(block, &symbols)) {
      llvm::errs() << "rejected valid live selected control/event syntax: " << block << '\n';
      return 1;
    }
  }
  for (llvm::StringRef block :
       {"begin if (data > gain) begin end end",
        "begin if (scalar[0] > gain) begin end end",
        "begin if (data[gain] > 0.0) begin end end",
        "begin if (data[4] > 0.0) begin end end",
        "begin if (data[counter] > gain) begin end counter = 1; else begin end end",
        "begin if (data[0] > gain) begin end else if (data[1] > gain) end end",
        "begin case (missing[0] > gain) 0: begin end endcase end",
        "begin case (data[4] > gain) default: begin end endcase end",
        "begin counter = (data[0] > gain ? (2147483647 + 1) : 1); end",
        "begin counter = (data[0] > gain ? 1 : (1 / 0)); end",
        "begin counter = (65536 * 65536); @(above(data[counter])) begin end end",
        "begin @(above(data)) begin end end",
        "begin @(cross(scalar[0], 1)) begin end end",
        "begin @(cross(data[missing], 1)) begin end end",
        "begin @(cross(data[0], 1, symbolic[-1], 0.01)) begin end end",
        "begin @(cross(data[0], 1, data[4], 0.01)) begin end end",
        "begin @(timer(data[0], data[1.0])) begin end end",
        "begin @(timer(data[0], missing[0])) begin end end",
        "begin @(cross(data[0], 1) or above(data[4])) begin end end",
        "begin @(above(data[0])) begin @(above(data[1])) begin end end end",
        "begin @(above(data[0])) begin end else begin end end",
        "begin @(above(data[0])) begin end counter = missing; end"}) {
    ++count;
    if (validBlock(block, &symbols)) {
      llvm::errs() << "accepted invalid live selected control/event syntax: " << block << '\n';
      return 1;
    }
  }

  // The live event helper is an independently verified private effect ABI. It
  // must never become an ordinary pure public function or trust a comment.
  const std::string helper =
      "analog function real event_live_store;\n"
      "input value;\noutput cache;\noutput ready;\n"
      "real value;\nreal cache;\ninteger ready;\n"
      "begin\ncache = value;\nready = 1;\nevent_live_store = value;\nend\nendfunction\n";
  nodal::AnalogEventPrivateFunctions privateFunctions;
  auto parsedHelper = nodal::reparseAnalogEventPrivateFunction(helper, privateFunctions);
  llvm::StringMap<unsigned> pureFunctions;
  if (mlir::failed(parsedHelper) || *parsedHelper != helper.size() ||
      !privateFunctions.names.contains("event_live_store") ||
      mlir::succeeded(nodal::reparseAnalogUserFunction(helper, pureFunctions)) ||
      !pureFunctions.empty() ||
      mlir::succeeded(nodal::reparseAnalogEventPrivateFunction(helper, privateFunctions)))
    return 1;
  count += 4;
  for (auto mutation :
       {std::make_pair("function real", "function integer"),
        std::make_pair("input value", "inout value"),
        std::make_pair("output cache", "input cache"),
        std::make_pair("output ready", "inout ready"),
        std::make_pair("real value;", "integer value;"),
        std::make_pair("real cache;", "integer cache;"),
        std::make_pair("integer ready;", "real ready;"),
        std::make_pair("real cache;", "real cache[0:1];"),
        std::make_pair("cache = value;", "cache = value + 1;"),
        std::make_pair("cache = value;", "cache = V(p);"),
        std::make_pair("cache = value;", "cache = hidden_root;"),
        std::make_pair("cache = value;", "cache = event_live_store(value, cache, ready);"),
        std::make_pair("ready = 1;", "ready = 0;"),
        std::make_pair("ready = 1;", "ready = 1; hidden_root = value;"),
        std::make_pair("ready = 1;", "ready = 1; $strobe(\"effect\");"),
        std::make_pair("ready = 1;", "ready = 1; @(initial_step) begin end"),
        std::make_pair("ready = 1;", "ready = 1; V(p) <+ value;"),
        std::make_pair("cache = value;\nready = 1;", "ready = 1; cache = value;"),
        std::make_pair("event_live_store = value;", "event_live_store = cache;"),
        std::make_pair("event_live_store = value;", "event_live_store = value; ready = 0;"),
        std::make_pair("real value;", "real value; real hidden;"),
        std::make_pair("event_live_store;", "user_store;"),
        std::make_pair("event_live_store;", "event_live_store_00;"),
        std::make_pair("event_live_store;", "event_live_store_bad;")}) {
    std::string invalid = helper;
    invalid.replace(invalid.find(mutation.first), std::string(mutation.first).size(),
                    mutation.second);
    nodal::AnalogEventPrivateFunctions fresh;
    ++count;
    if (mlir::succeeded(nodal::reparseAnalogEventPrivateFunction(invalid, fresh)) ||
        !fresh.names.empty()) {
      llvm::errs() << "accepted mutated live helper: " << invalid << '\n';
      return 1;
    }
  }
  std::string suffixedHelper = helper;
  size_t helperPosition = 0;
  while ((helperPosition = suffixedHelper.find("event_live_store", helperPosition)) !=
         std::string::npos) {
    helperPosition += std::string("event_live_store").size();
    suffixedHelper.insert(helperPosition, "_12");
    helperPosition += 3;
  }
  nodal::AnalogEventPrivateFunctions suffixedFunctions;
  if (mlir::failed(nodal::reparseAnalogEventPrivateFunction(suffixedHelper, suffixedFunctions)))
    return 1;
  ++count;

  auto liveSymbols = symbols;
  for (unsigned index = 0; index < 4; ++index) {
    const auto suffix = std::to_string(index);
    if (mlir::failed(nodal::reparseAnalogEventDeclaration("real event_live_cache_" + suffix + ";",
                                                          liveSymbols)) ||
        mlir::failed(nodal::reparseAnalogEventDeclaration(
            "integer event_live_ready_" + suffix + ";", liveSymbols)))
      return 1;
    count += 2;
  }
  for (llvm::StringRef declaration :
       {"integer event_live_cache_4;", "real event_live_ready_4;", "genvar event_live_ready_4;",
        "real event_live_cache_4[0:1];", "real event_live_cache_4 = 0;",
        "integer event_live_ready_4 = 0;"}) {
    ++count;
    if (mlir::succeeded(nodal::reparseAnalogEventDeclaration(declaration, liveSymbols)))
      return 1;
  }
  auto call = [](llvm::StringRef input, unsigned index) {
    auto suffix = std::to_string(index);
    return "event_live_store(" + input.str() + ", event_live_cache_" + suffix +
           ", event_live_ready_" + suffix + ")";
  };
  auto demand = [&](llvm::StringRef input, unsigned index) {
    auto suffix = std::to_string(index);
    return "(event_live_ready_" + suffix + " ? event_live_cache_" + suffix + " : " +
           call(input, index) + ")";
  };
  auto liveBlock = [](llvm::StringRef expression) {
    return "begin @(above(" + expression.str() + ")) begin end end";
  };
  auto validLive = [&](llvm::StringRef source, const nodal::AnalogEventSymbols &environment) {
    auto functions = privateFunctions;
    auto result = nodal::reparseAnalogEventBlock(source, &environment, &functions);
    return mlir::succeeded(result) && *result == source.size();
  };
  const auto leaf = demand("data[counter]", 3);
  const auto diamond = demand(demand(leaf + " + 1", 1) + " + " + demand(leaf + " + 2", 2), 0);
  for (const auto &block :
       {liveBlock(demand("V(p)", 0)), liveBlock(demand("$abstime", 0)),
        liveBlock(demand("$temperature", 0)),
        liveBlock(demand("($simparam(\"tnom\") + 273.15)", 0)), liveBlock(diamond),
        liveBlock("0 && " + demand("data[0]", 0)), liveBlock("1 ? 2 : " + demand("data[0]", 0)),
        liveBlock(demand("data[0]", 0) + " + " + demand("data[0]", 1)),
        liveBlock(demand("data[0] + 1", 0) + " + " + demand("data [ 0 ] + 1", 0)),
        "begin event_live_ready_0 = 0; @(cross(" + demand("data[0]", 0) + ", 1) or above(" +
            demand("data[0]", 0) + ")) begin scalar = data[1]; end end"}) {
    ++count;
    if (!validLive(block, liveSymbols)) {
      llvm::errs() << "rejected valid private helper demand: " << block << '\n';
      return 1;
    }
  }
  for (const auto &block :
       {liveBlock(call("$abstime()", 0)),
        liveBlock(call("$temperature()", 0)),
        liveBlock(call("$simparam(\"tnom\", 27.0)", 0)),
        liveBlock(call("event_live_cache_0", 0)),
        liveBlock(call("event_live_ready_0", 0)),
        liveBlock(call("data[event_live_ready_0]", 0)),
        liveBlock(call("0 && event_live_cache_0", 0)),
        liveBlock(call(call("data[0]", 0), 0)),
        liveBlock(call(call("data[0]", 0), 1) + " + " + call("data[1]", 0)),
        liveBlock("event_live_store(data[0], scalar, counter)"),
        liveBlock("event_live_store(data[0], event_live_cache_0, event_live_ready_1)"),
        liveBlock("event_live_store(data[0], event_live_cache_0, event_live_cache_0)"),
        liveBlock("event_live_store(data[0], event_live_cache_0[0], event_live_ready_0)"),
        liveBlock("event_live_store(data[0], event_live_ready_0, event_live_cache_0)"),
        liveBlock("event_live_store(data[0], event_live_cache_0 + 0, event_live_ready_0)"),
        liveBlock("event_live_store(data[0], event_live_cache_0)"),
        liveBlock("event_live_store(data[0], event_live_cache_0, event_live_ready_0, 0)"),
        liveBlock(call("missing", 0)),
        liveBlock(call("ddt(V(p))", 0)),
        liveBlock(call("data[4]", 0)),
        "begin scalar = " + call("data[0]", 0) + "; end",
        "begin if (" + call("data[0]", 0) + ") begin end end",
        "begin @(initial_step) begin scalar = " + call("data[0]", 0) + "; end end",
        liveBlock(call("data[0]", 0) + " + " + call("data[1]", 0))}) {
    ++count;
    if (validLive(block, liveSymbols)) {
      llvm::errs() << "accepted invalid private helper demand: " << block << '\n';
      return 1;
    }
  }
  for (unsigned mutation = 0; mutation < 4; ++mutation) {
    auto forged = liveSymbols;
    auto &cache = forged.find("event_live_cache_0")->second;
    if (mutation == 0)
      cache.privateLive = false;
    else if (mutation == 1)
      cache.writable = false;
    else if (mutation == 2)
      cache.kind = AnalogEventSymbolKind::Integer;
    else
      cache.lowerBound = 0;
    ++count;
    if (validLive(liveBlock(call("data[0]", 0)), forged))
      return 1;
  }
  if (validBlock(liveBlock(call("data[0]", 0)), &liveSymbols) ||
      mlir::succeeded(nodal::reparseAnalogEventBlock(liveBlock(call("data[0]", 0)), nullptr,
                                                     &privateFunctions)) ||
      mlir::succeeded(nodal::reparseAnalogUserExpression(call("1", 0), pureFunctions)) ||
      mlir::succeeded(nodal::reparseAnalogNoiseCall("white_noise(" + call("1", 0) + ", \"n\")")) ||
      mlir::succeeded(
          nodal::reparseAnalogTransferCall("laplace_nd(" + call("1", 0) + ", '{1}, '{1})")))
    return 1;
  count += 5;

  // Resource checks use actual emitted syntax, with no source graph or comment
  // supplying counts. Exact boundaries remain admitted; failure is transactional.
  std::string repeated = call("data[0]", 0);
  for (unsigned index = 1; index < 4096; ++index)
    repeated += " + " + call("data[0]", 0);
  if (!validLive(liveBlock(repeated), liveSymbols) ||
      validLive(liveBlock(repeated + " + " + call("data[0]", 0)), liveSymbols) ||
      validLive(liveBlock(std::string(1024 * 1024, ' ') + call("data[0]", 0)), liveSymbols) ||
      validLive(liveBlock(std::string(260, '(') + call("data[0]", 0) + std::string(260, ')')),
                liveSymbols))
    return 1;
  count += 4;
  const auto single = liveBlock(call("data[0]", 0));
  auto boundary = privateFunctions;
  boundary.demandWrappers = 65535;
  if (mlir::failed(nodal::reparseAnalogEventBlock(single, &liveSymbols, &boundary)) ||
      boundary.demandWrappers != 65536 ||
      mlir::succeeded(nodal::reparseAnalogEventBlock(single, &liveSymbols, &boundary)) ||
      boundary.demandWrappers != 65536)
    return 1;
  boundary = privateFunctions;
  const uint64_t expressionBytes = std::string("above(").size() + call("data[0]", 0).size() + 1;
  if (!validLive(liveBlock(std::string(1024 * 1024 - expressionBytes, ' ') + call("data[0]", 0)),
                 liveSymbols))
    return 1;
  boundary.expressionBytes = 16 * 1024 * 1024 - expressionBytes;
  if (mlir::failed(nodal::reparseAnalogEventBlock(single, &liveSymbols, &boundary)) ||
      boundary.expressionBytes != 16 * 1024 * 1024 ||
      mlir::succeeded(nodal::reparseAnalogEventBlock(single, &liveSymbols, &boundary)) ||
      boundary.expressionBytes != 16 * 1024 * 1024)
    return 1;
  count += 5;

  const std::string targetPrefix =
      "module Live;\nelectrical p;\nreal event_live_cache_0;\ninteger event_live_ready_0;\n";
  const std::string targetEvent =
      "analog begin\nbegin : event_procedure\nevent_live_ready_0 = 0;\n@(above(" +
      demand("V(p)", 0) + ")) begin\nend\nend\nend\nendmodule\n";
  const std::string purePrefixFunction =
      "analog function real event_live_store;\ninput value;\nreal value;\nbegin\n"
      "event_live_store = value;\nend\nendfunction\n";
  auto scopedTarget = [&](llvm::StringRef analog) {
    return "module ScopedLive;\nelectrical p;\n" + helper +
           "genvar g;\ngenerate\nfor (g = 0; g < 2; g = g + 1) begin : lanes\n"
           "real event_live_cache_0;\ninteger event_live_ready_0;\n" +
           analog.str() + "end\nendgenerate\nendmodule\n";
  };
  const auto scopedAnalog =
      llvm::StringRef(targetEvent).drop_back(std::string("endmodule\n").size());
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    const nodal::BackendConfiguration configuration{
        &nodal::getBackendProfile(kind), nodal::GateProfile::Default,
        nodal::ShapedValueLayout::ScalarOrFlat, nodal::MaterializationPolicy::SafeInline,
        nodal::NamingPolicy::Semantic};
    if (mlir::failed(
            nodal::reparseBackendTarget(targetPrefix + helper + targetEvent, configuration)) ||
        mlir::failed(nodal::reparseBackendTarget(scopedTarget(scopedAnalog), configuration)) ||
        mlir::failed(nodal::reparseBackendTarget(
            targetPrefix + purePrefixFunction + suffixedHelper +
                "analog begin\nV(p) <+ event_live_store(1);\nend\nendmodule\n",
            configuration)))
      return 1;
    count += 3;
    for (const auto &invalid :
         {targetPrefix + "// compiler-private event_live_store\n" + targetEvent,
          targetPrefix + helper + "analog begin\nV(p) <+ " + call("1", 0) + ";\nend\nendmodule\n",
          targetPrefix + helper + "real w;\nanalog begin\nw = transition(" + call("1", 0) +
              ");\nend\nendmodule\n",
          targetPrefix + helper + "analog begin\n$bound_step(" + call("1", 0) +
              ");\nend\nendmodule\n",
          targetPrefix + helper + "parameter real x = " + call("1", 0) + ";\n" + targetEvent,
          targetPrefix + helper + helper + targetEvent,
          targetPrefix + helper + purePrefixFunction + targetEvent,
          targetPrefix + purePrefixFunction + helper + targetEvent,
          targetPrefix + helper + "real event_live_store;\n" + targetEvent,
          targetPrefix + helper + "parameter real event_live_store = 0;\n" + targetEvent,
          scopedTarget("analog begin\nV(p) <+ " + call("1", 0) + ";\nend\n")}) {
      ++count;
      if (mlir::succeeded(nodal::reparseBackendTarget(invalid, configuration))) {
        llvm::errs() << "accepted private helper outside its target contract: " << invalid << '\n';
        return 1;
      }
    }
  }

  // The structural target parser has deliberately broader continuous profiles.
  // New context queries must still cross the independent exact-query gate,
  // without opting indexed/generated endpoints into a scalar-only grammar.
  const std::string contextPrefix = "module ContextQueries;\nelectrical p;\n";
  auto continuousContext = [&](llvm::StringRef value) {
    return contextPrefix + "analog begin\nV(p) <+ " + value.str() + ";\nend\nendmodule\n";
  };
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    const nodal::BackendConfiguration configuration{
        &nodal::getBackendProfile(kind), nodal::GateProfile::Default,
        nodal::ShapedValueLayout::ScalarOrFlat, nodal::MaterializationPolicy::SafeInline,
        nodal::NamingPolicy::Semantic};
    for (const auto &target :
         {continuousContext("$temperature"), continuousContext("($simparam(\"tnom\") + 273.15)"),
          continuousContext("($temperature - ($simparam(\"tnom\") + 273.15))"),
          continuousContext("(analysis(\"tran\") ? $temperature : $abstime)"),
          contextPrefix + "electrical [0:1] pins;\nanalog begin\n"
                          "V(pins[0], p) <+ ($temperature * V(pins[1], p));\nend\nendmodule\n",
          contextPrefix + "electrical [0:1] pins;\ngenvar g;\nanalog begin\n"
                          "for (g = 0; g < 2; g = g + 1) begin : lanes\n"
                          "V(pins[g], p) <+ ($simparam(\"tnom\") + 273.15);\n"
                          "end\nend\nendmodule\n",
          contextPrefix + "real event_value;\nanalog begin\nbegin : event_read\n"
                          "@(initial_step) begin event_value = $temperature; end\n"
                          "event_value = $abstime;\nend\nend\nendmodule\n",
          contextPrefix + "real w;\nanalog begin\nw = transition($temperature);\n"
                          "V(p) <+ w;\nend\nendmodule\n"}) {
      ++count;
      if (mlir::failed(nodal::reparseBackendTarget(target, configuration))) {
        llvm::errs() << "rejected valid runtime context target: " << target << '\n';
        return 1;
      }
    }
    for (llvm::StringRef query :
         {"$temperature()", "$temperature(0)", "$temperature_extra", "$simparam", "$simparam()",
          "$simparam(\"tnom\", 27.0)", "$simparam(\"temperature\")", "$simparam(\"corner\")",
          "$simparam(\"TNOM\")", "$simparam(tnom)", "$simparam(\"tnom\")()", "$temperature[0]",
          "$simparam(\"tnom\")[0]", "$temperature.member"}) {
      for (const auto &target :
           {continuousContext(query), contextPrefix + "real w;\nanalog begin\nw = transition(" +
                                          query.str() + ");\nV(p) <+ w;\nend\nendmodule\n"}) {
        ++count;
        if (mlir::succeeded(nodal::reparseBackendTarget(target, configuration))) {
          llvm::errs() << "accepted malformed runtime context target: " << target << '\n';
          return 1;
        }
      }
    }
    for (llvm::StringRef value :
         {"$temperature", "($simparam(\"tnom\") + 273.15)", "$abstime", "sqrt($temperature)",
          "(0 * $temperature)", "(1 ? 0.0 : $temperature)"}) {
      for (const auto &target :
           {contextPrefix + "parameter real TEMP = " + value.str() + ";\nendmodule\n",
            contextPrefix + "localparam real TEMP = " + value.str() + ";\nendmodule\n",
            contextPrefix + "real event_value = " + value.str() + ";\nendmodule\n",
            contextPrefix + "real event_values[0:1] = '{2{" + value.str() + "}};\nendmodule\n",
            contextPrefix + "analog function real bad;\ninput x;\nreal x;\nbegin\nbad = " +
                value.str() + ";\nend\nendfunction\nendmodule\n"}) {
        ++count;
        if (mlir::succeeded(nodal::reparseBackendTarget(target, configuration))) {
          llvm::errs() << "accepted static or pure runtime context target: " << target << '\n';
          return 1;
        }
      }
    }
  }

  llvm::outs() << "Foundation 43 event-array reparse: " << count << " cases passed\n";
  return 0;
}
