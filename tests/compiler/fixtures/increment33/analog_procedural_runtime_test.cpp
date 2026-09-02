#include <type_traits>
#include "nodal/AnalogProceduralRuntime.h"

#include <cassert>
#include <iostream>
#include <optional>
#include <string>
#include <vector>

using namespace nodal::analog::procedural;

namespace {

const ValueType RealVoltage{ScalarKind::Real, "voltage"};
const ValueType RealCurrent{ScalarKind::Real, "current"};
const ValueType IntegerDimensionless{ScalarKind::Integer, "dimensionless"};
const ValueType BoolDimensionless{ScalarKind::Boolean, "dimensionless"};

template <typename Body> void expect(const std::string &code, Body &&body) {
  bool caught = false;
  try {
    std::forward<Body>(body)();
  } catch (const Failure &failure) {
    caught = true;
    assert(failure.diagnostic().code == code);
  }
  assert(caught);
}

} // namespace

int main() {
  Recorder recorder("ProceduralTop");
  std::optional<Variable> escaped;

  recorder.procedure([&] {
    const Variable previous = recorder.declareVariable(
        "previous", RealVoltage, Value{"0.0V", RealVoltage, {}},
        Source{"analog_procedural_runtime_test.cpp", 35, 5});
    const Variable scratch = recorder.declareVariable("scratch", RealVoltage);
    const Variable count = recorder.declareVariable(
        "count", IntegerDimensionless, Value{"0", IntegerDimensionless, {}});

    recorder.assign("capture", scratch, recorder.read(previous));
    recorder.assign("update", previous, Value{"1.25V", RealVoltage, {}},
                    Value{"enabled", BoolDimensionless, {}}, {"dc", "transient"});
    recorder.assign("repeat-update", previous, Value{"2.50V", RealVoltage, {}});
    recorder.assign("promote-count", scratch,
                    Value{"count-as-real", RealVoltage, {count}});

    recorder.scope("inner", [&] {
      const Variable local = recorder.declareVariable(
          "local", RealVoltage, Value{"0.5V", RealVoltage, {}});
      escaped = local;
      recorder.assign("inner-write", scratch, recorder.read(local));
    });

    expect("NODAL-ANALOG-033-010", [&] {
      recorder.assign("scope-escape", *escaped, Value{"1.0V", RealVoltage, {}});
    });
  });

  const Snapshot snapshot = recorder.snapshot();
  assert(snapshot.variables.size() == 4);
  assert(snapshot.assignments.size() == 5);
  for (std::size_t index = 0; index < snapshot.variables.size(); ++index)
    assert(snapshot.variables[index].declarationOrder == index);
  for (std::size_t index = 0; index < snapshot.assignments.size(); ++index)
    assert(snapshot.assignments[index].authoredOrder == index);
  assert((std::vector<std::size_t>{snapshot.variables[0].operationOrder,
                                   snapshot.variables[1].operationOrder,
                                   snapshot.variables[2].operationOrder,
                                   snapshot.variables[3].operationOrder} ==
          std::vector<std::size_t>{0, 1, 2, 7}));
  assert((std::vector<std::size_t>{snapshot.assignments[0].operationOrder,
                                   snapshot.assignments[1].operationOrder,
                                   snapshot.assignments[2].operationOrder,
                                   snapshot.assignments[3].operationOrder,
                                   snapshot.assignments[4].operationOrder} ==
          std::vector<std::size_t>{3, 4, 5, 6, 8}));
  assert(snapshot.assignments[0].identity == "ProceduralTop.capture");
  assert(snapshot.assignments[1].identity == "ProceduralTop.update");
  assert(snapshot.assignments[2].identity == "ProceduralTop.repeat-update");
  assert(snapshot.assignments[3].identity == "ProceduralTop.promote-count");
  assert(snapshot.assignments[4].identity == "ProceduralTop.inner-write");
  assert(snapshot.assignments[1].guard.has_value());
  assert(snapshot.assignments[1].analyses == std::vector<std::string>({"dc", "transient"}));

  Recorder readBeforeWrite("ReadBeforeWrite");
  expect("NODAL-ANALOG-033-011", [&] {
    readBeforeWrite.procedure([&] {
      const Variable value = readBeforeWrite.declareVariable("uninitialized", RealVoltage);
      (void)readBeforeWrite.read(value);
    });
  });

  Recorder dimensionMismatch("DimensionMismatch");
  expect("NODAL-ANALOG-033-013", [&] {
    dimensionMismatch.procedure([&] {
      const Variable voltage = dimensionMismatch.declareVariable("voltage", RealVoltage);
      dimensionMismatch.assign("bad-dimension", voltage,
                               Value{"1.0A", RealCurrent, {}});
    });
  });

  Recorder ownerA("OwnerA");
  std::optional<Variable> foreign;
  ownerA.procedure([&] {
    foreign = ownerA.declareVariable("foreign", RealVoltage,
                                     Value{"0.0V", RealVoltage, {}});
  });
  Recorder ownerB("OwnerB");
  expect("NODAL-ANALOG-033-009", [&] {
    ownerB.procedure([&] {
      ownerB.assign("foreign-write", *foreign, Value{"1.0V", RealVoltage, {}});
    });
  });

  Recorder outside("Outside");
  expect("NODAL-ANALOG-033-008", [&] {
    (void)outside.declareVariable("illegal", RealVoltage);
  });

  Recorder multipleProcedures("MultipleProcedures");
  multipleProcedures.procedure([] {});
  expect("NODAL-ANALOG-033-020", [&] {
    multipleProcedures.procedure([] {});
  });

  std::cout << "Increment 33 native procedural runtime witness passed\n";
  return 0;
}
