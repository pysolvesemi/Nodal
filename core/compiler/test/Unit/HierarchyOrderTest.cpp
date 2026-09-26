#include "nodal/Support/HierarchyOrder.h"

#include <algorithm>
#include <iostream>
#include <vector>

int main() {
  using Status = nodal::HierarchyOrder::Status;
  unsigned checks = 0;
  auto check = [&](bool value, const char *name) {
    ++checks;
    if (!value)
      std::cerr << "FAIL: " << name << '\n';
    return value;
  };
  bool ok = true;
  auto empty = nodal::orderHierarchy({});
  ok &= check(empty.status == Status::Success && empty.leavesFirst.empty(), "empty");
  auto diamond = nodal::orderHierarchy({{1, 2}, {3}, {3}, {}});
  ok &= check(diamond.status == Status::Success &&
                  diamond.leavesFirst == std::vector<std::size_t>({3, 1, 2, 0}),
              "diamond has one shared leaf");
  auto cycle = nodal::orderHierarchy({{1}, {2}, {0}});
  const bool cycleKind = cycle.status == Status::Cycle && cycle.source == 2;
  const bool cycleTarget = cycle.target == 0 && cycle.leavesFirst.empty();
  ok &= check(cycleKind && cycleTarget, "cycle discards partial ordering");
  auto missing = nodal::orderHierarchy({{}, {4}});
  const bool missingKind = missing.status == Status::UnknownTarget && missing.source == 1;
  const bool missingTarget = missing.target == 4 && missing.leavesFirst.empty();
  ok &= check(missingKind && missingTarget, "unknown target");
  auto disconnected = nodal::orderHierarchy({{}, {}, {2}});
  ok &= check(disconnected.status == Status::Cycle && disconnected.leavesFirst.empty(),
              "disconnected self-cycle");
  constexpr std::size_t size = 100000;
  std::vector<std::vector<std::size_t>> deep(size);
  for (std::size_t i = 0; i + 1 < size; ++i)
    deep[i].push_back(i + 1);
  auto ordered = nodal::orderHierarchy(deep);
  bool reversed = ordered.leavesFirst.size() == size;
  for (std::size_t i = 0; i < ordered.leavesFirst.size(); ++i)
    reversed &= ordered.leavesFirst[i] == size - i - 1;
  ok &= check(ordered.status == Status::Success && reversed, "100000-deep stack-safe traversal");
  ok &= check(nodal::orderHierarchy(deep).leavesFirst == ordered.leavesFirst, "repeat stability");
  deep.back().push_back(size / 2);
  ok &= check(nodal::orderHierarchy(deep).status == Status::Cycle, "deep back edge");
  std::vector<std::vector<std::size_t>> wide(size);
  for (std::size_t i = 1; i < size; ++i) {
    wide[0].push_back(i);
    wide[0].push_back(i);
  }
  auto fanout = nodal::orderHierarchy(wide);
  const bool fanoutShape = fanout.status == Status::Success && fanout.leavesFirst.size() == size;
  const bool fanoutRoot = fanout.leavesFirst.back() == 0;
  ok &= check(fanoutShape && fanoutRoot, "repeated wide instances");
  std::cout << checks << " hierarchy-order checks " << (ok ? "passed" : "failed") << '\n';
  return ok ? 0 : 1;
}
