#include "nodal/AnalogEquationRuntime.h"

#include <cassert>
#include <string>
#include <vector>

namespace {

using nodal::analog::ContributionKind;
using nodal::analog::ContributionTarget;
using nodal::analog::Expression;
using nodal::analog::Metadata;
using nodal::analog::Recorder;
using nodal::analog::RegionKind;
using nodal::analog::SemanticError;
using nodal::analog::SourceSpan;
using nodal::analog::ValueKind;

Metadata metadata(int line) {
  return Metadata{"fixture.device", std::nullopt, {"dc", "transient"},
                  "continuous",
                  SourceSpan{"analog_equation_runtime_test.cpp", line, 1}};
}

Metadata initialMetadata(int line) {
  return Metadata{"fixture.device", std::nullopt, {"initialization"},
                  "continuous",
                  SourceSpan{"analog_equation_runtime_test.cpp", line, 1}};
}

Expression real(std::string text, std::string dimension) {
  return Expression{std::move(text), std::move(dimension), ValueKind::Real};
}

Recorder build(const std::vector<std::string> &order) {
  Recorder recorder;
  {
    [[maybe_unused]] auto region = recorder.region(RegionKind::Equation);
    recorder.recordEquation("ohms-law", real("V(p,n)", "V"),
                            real("R * I(p,n)", "V"), metadata(20));
  }
  {
    [[maybe_unused]] auto region =
        recorder.region(RegionKind::InitialEquation);
    recorder.recordEquation("initial-voltage", real("V(p,n)", "V"),
                            real("0.0", "V"), initialMetadata(21));
  }
  {
    [[maybe_unused]] auto region = recorder.region(RegionKind::Contribution);
    for (const auto &identity : order) {
      recorder.recordContribution(
          identity,
          ContributionTarget{"branch:p->n", ContributionKind::Flow, "A",
                             "p-to-n"},
          real(identity == "source-a" ? "1.0" : "2.0", "A"),
          metadata(22));
    }
  }
  return recorder;
}

} // namespace

int main() {
  const auto first = build({"source-a", "source-b"}).snapshot();
  const auto second = build({"source-b", "source-a"}).snapshot();

  assert(first.equations.size() == 2);
  assert(first.equations.at(1).identity == "ohms-law");
  assert(first.equations.at(1).residual.authoredLeft.rendered == "V(p,n)");
  assert(first.equations.at(1).residual.authoredRight.rendered ==
         "R * I(p,n)");
  assert(!first.equations.at(1).residual.causallyOriented);
  assert(!first.equations.at(1).residual.divided);
  assert(first.equations.at(0).metadata.analyses ==
         std::set<std::string>{"initialization"});
  assert(first.contributions.size() == 1);
  assert(second.contributions.size() == 1);
  assert(first.contributions.front().terms.at(0).identity == "source-a");
  assert(first.contributions.front().terms.at(1).identity == "source-b");
  assert(second.contributions.front().terms.at(0).identity == "source-a");
  assert(second.contributions.front().terms.at(1).identity == "source-b");

  bool rejected = false;
  try {
    Recorder recorder;
    [[maybe_unused]] auto region = recorder.region(RegionKind::Procedural);
    recorder.recordContribution(
        "illegal",
        ContributionTarget{"branch:p->n", ContributionKind::Flow, "A",
                           "p-to-n"},
        real("1.0", "A"), metadata(40));
  } catch (const SemanticError &error) {
    rejected = error.code() == "NODAL-ANALOG-032-012";
  }
  assert(rejected);

  rejected = false;
  try {
    Recorder recorder;
    [[maybe_unused]] auto region =
        recorder.region(RegionKind::InitialEquation);
    recorder.recordEquation("runtime-initial", real("V(p,n)", "V"),
                            real("0.0", "V"), metadata(41));
  } catch (const SemanticError &error) {
    rejected = error.code() == "NODAL-ANALOG-133-009";
  }
  assert(rejected);

  rejected = false;
  try {
    Recorder recorder;
    [[maybe_unused]] auto region = recorder.region(RegionKind::Equation);
    recorder.recordEquation("", real("V(p,n)", "V"),
                            real("0.0", "V"), metadata(42));
  } catch (const SemanticError &error) {
    rejected = error.code() == "NODAL-ANALOG-032-015";
  }
  assert(rejected);

  rejected = false;
  try {
    Recorder recorder;
    [[maybe_unused]] auto region = recorder.region(RegionKind::Contribution);
    recorder.recordContribution(
        "", ContributionTarget{"branch:p->n", ContributionKind::Flow, "A",
                               "p-to-n"},
        real("1.0", "A"), metadata(43));
  } catch (const SemanticError &error) {
    rejected = error.code() == "NODAL-ANALOG-032-016";
  }
  assert(rejected);
  return 0;
}
