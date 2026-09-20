#include "nodal/Backend/AnalogEventBackend.h"
#include "nodal/Backend/AnalogUserFunctionBackend.h"

#include "llvm/ADT/StringMap.h"
#include "llvm/Support/raw_ostream.h"

#include <initializer_list>
#include <string>

int main() {
  llvm::StringMap<unsigned> functions;
  llvm::StringRef basic =
      "analog function real scaleSignal; input signal, factor; real signal; real factor; real "
      "scaled; begin scaled = (signal * factor); scaleSignal = scaled; end endfunction\n";
  auto parsed = nodal::reparseAnalogUserFunction(basic, functions);
  if (mlir::failed(parsed) || *parsed != basic.size() || functions.lookup("scaleSignal") != 2) {
    llvm::errs() << "valid typed function was rejected\n";
    return 1;
  }
  unsigned count = 1;
  for (llvm::StringRef body :
       {"analog function real halfSignal; input x; real x; begin halfSignal = (x * (1.0 / 2.0)); "
        "end endfunction",
        "analog function real outerSignal; input x; real x; begin outerSignal = scaleSignal(x, "
        "0.5); end endfunction",
        "analog function integer nextCount; input count; integer count; integer next; begin next = "
        "(count + 1); nextCount = next; end endfunction",
        "analog function real sqrtSignal; input x; real x; begin sqrtSignal = sqrt((x * x)); end "
        "endfunction",
        "analog function real chooseSignal; input x; real x; begin chooseSignal = ((x > 0.0) ? x : "
        "(-x)); end endfunction"}) {
    if (mlir::failed(nodal::reparseAnalogUserFunction(body, functions))) {
      llvm::errs() << "valid function rejected: " << body << '\n';
      return 1;
    }
    ++count;
  }
  for (llvm::StringRef body :
       {"analog function real bad; input x; real x; begin end endfunction",
        "analog function real bad; input x; real x; begin bad = hidden; end endfunction",
        "analog function real bad; input x; real x; begin bad = bad(x); end endfunction",
        "analog function real bad; input x; real x; begin bad = unknown(x); end endfunction",
        "analog function real bad; input x; real x; begin bad = scaleSignal(x); end endfunction",
        "analog function real bad; input x; real x; begin bad = scaleSignal(x, x, x); end "
        "endfunction",
        "analog function real bad; input x; real x; begin bad = ddt(x); end endfunction",
        "analog function real bad; input x; real x; begin bad = V(x); end endfunction",
        "analog function real bad; input x; real x; begin bad = analysis(\"tran\"); end "
        "endfunction",
        "analog function real bad; input x; real x; begin bad = $abstime; end endfunction",
        "analog function real bad; input x; real x; real localValue; begin bad = localValue; "
        "localValue = x; end endfunction",
        "analog function real bad; input x; real x; real localValue; begin localValue = "
        "localValue; bad = x; end endfunction",
        "analog function real bad; input x; real x; begin x = 1.0; bad = x; end endfunction",
        "analog function real bad; input x; real x; begin bad = x; bad = x; end endfunction",
        "analog function real bad; input x, x; real x; begin bad = x; end endfunction",
        "analog function real bad; input x; begin bad = x; end endfunction",
        "analog function real bad; input x; real x; real x; begin bad = x; end endfunction",
        "analog function real bad; input x; real x; real y; begin bad = x; end endfunction",
        "analog function real bad; output x; real x; begin bad = x; end endfunction",
        "analog function real input; input x; real x; begin input = x; end endfunction",
        "analog function real bad; input x; real x; begin bad = 1e999; end endfunction",
        "analog function real bad; input x; real x; begin bad = sqrt(); end endfunction"}) {
    if (mlir::succeeded(nodal::reparseAnalogUserFunction(body, functions)) ||
        functions.contains("bad")) {
      llvm::errs() << "invalid function accepted or polluted signatures: " << body << '\n';
      return 1;
    }
    ++count;
  }
  for (llvm::StringRef expression :
       {"scaleSignal(V(p, n), 2.0)", "outerSignal(scaleSignal(V(p), 1.0))", "nextCount(2)",
        "(scaleSignal(V(p), 2.0) + V(n))"}) {
    if (mlir::failed(nodal::reparseAnalogUserExpression(expression, functions))) {
      llvm::errs() << "valid call rejected: " << expression << '\n';
      return 1;
    }
    ++count;
  }
  for (llvm::StringRef expression : {"scaleSignal(V(p))", "missing(V(p))",
                                     "scaleSignal(V(p), 2, 3)", "scaleSignal(V(p), 2); $finish;"}) {
    if (mlir::succeeded(nodal::reparseAnalogUserExpression(expression, functions))) {
      llvm::errs() << "invalid call accepted: " << expression << '\n';
      return 1;
    }
    ++count;
  }
  if (mlir::failed(nodal::reparseAnalogTransferCall(
          "laplace_nd(scaleSignal(V(p), 2.0), '{1}, '{1})", &functions)) ||
      mlir::succeeded(nodal::reparseAnalogTransferCall("laplace_nd(scaleSignal(V(p)), '{1}, '{1})",
                                                       &functions)))
    return 1;
  llvm::outs() << "Increment 41 independent function parser: " << count + 2 << " cases passed\n";
  return 0;
}
