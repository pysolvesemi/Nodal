#include "nodal/Backend/AnalogEventBackend.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <initializer_list>

int main() {
  unsigned count = 0;
  for (llvm::StringRef call :
       {"laplace_nd(V(p, n), '{gain}, '{1, tau})",
        "laplace_nd(transfer_0, '{0, 1, 0}, '{-2, 1, 0})",
        "laplace_nd((V(p, n) + noise_0), '{sqrt(4)}, '{1})",
        "laplace_nd(ddt(V(p, n)), '{1}, '{1, tau})", "laplace_nd(idt(V(p, n), 0), '{1}, '{1, tau})",
        "laplace_nd(I(<p>), '{1}, '{1})", "laplace_nd($abstime, '{1}, '{1})",
        "zi_nd(V(p), '{1}, '{1}, 1e-3)", "zi_nd(transfer_0, '{0.5, 0.5}, '{1, -0.1}, 1e-3, 1e-5)",
        "zi_nd((V(p) > 1 ? V(p) : V(n)), '{1}, '{1}, 1, 0.1, 0)"}) {
    ++count;
    if (mlir::failed(nodal::reparseAnalogTransferCall(call))) {
      llvm::errs() << "rejected valid transfer call: " << call << '\n';
      return 1;
    }
  }
  for (llvm::StringRef call : {"laplace_nd(V(p), '{}, '{1})",
                               "laplace_nd(V(p), '{1}, '{})",
                               "laplace_nd(V(p), {1}, '{1})",
                               "laplace_nd(V(p), '{1}, {1})",
                               "laplace_nd(V(p), '{1,}, '{1})",
                               "laplace_nd(V(p), '{1}, '{1}, 1)",
                               "laplace_nd(V(p), '{1}, '{1}); $finish;",
                               "laplace_nd($random(), '{1}, '{1})",
                               "laplace_nd(V(p), '{1e999}, '{1})",
                               "laplace_nd(V(p), '{1}, '{1}) trailing",
                               "laplace_zp(V(p), '{1, 0}, '{1, 0})",
                               "zi_np(V(p), '{1}, '{1, 0}, 1)",
                               "zi_nd(V(p), '{1}, '{1})",
                               "zi_nd(V(p), '{1}, '{1}, 1,)",
                               "zi_nd(V(p), '{1}, '{1}, 1, 1, 1, 1)",
                               "zi_nd(V(p), '{1}, '{1},)",
                               "zi_nd(V(p), '{1}, '{1}, 1); $finish;",
                               "laplace_nd(ddt(V(p), 1), '{1}, '{1})",
                               "laplace_nd(idt(V(p), 0, 1), '{1}, '{1})",
                               "laplace_nd(V(<p>), '{1}, '{1})",
                               "laplace_nd($abstime(), '{1}, '{1})"}) {
    ++count;
    if (mlir::succeeded(nodal::reparseAnalogTransferCall(call))) {
      llvm::errs() << "accepted malformed transfer call: " << call << '\n';
      return 1;
    }
  }
  for (llvm::StringRef call : {"begin\nx = ddt(V(p));\nend\n", "begin\nx = idt(V(p));\nend\n"}) {
    if (mlir::succeeded(nodal::reparseAnalogEventBlock(call))) {
      llvm::errs() << "transfer expression grammar leaked into procedural events\n";
      return 1;
    }
  }
  llvm::outs() << "Increment 40 transfer reparse: " << count << " cases passed\n";
  return 0;
}
