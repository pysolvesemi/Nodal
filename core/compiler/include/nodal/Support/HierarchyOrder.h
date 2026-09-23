#ifndef NODAL_SUPPORT_HIERARCHYORDER_H
#define NODAL_SUPPORT_HIERARCHYORDER_H

#include <cstddef>
#include <utility>
#include <vector>

namespace nodal {

/// Input indices and adjacency order define deterministic diagnostic tie-breaking.
/// This iterative DFS is linear in definitions plus instance edges, not hierarchy depth.
struct HierarchyOrder {
  enum class Status { Success, UnknownTarget, Cycle };
  Status status = Status::Success;
  std::size_t source = 0;
  std::size_t target = 0;
  std::vector<std::size_t> leavesFirst;
};

inline HierarchyOrder orderHierarchy(const std::vector<std::vector<std::size_t>> &edges) {
  HierarchyOrder result;
  std::vector<unsigned char> colors(edges.size(), 0);
  std::vector<std::pair<std::size_t, std::size_t>> stack;
  result.leavesFirst.reserve(edges.size());
  for (std::size_t root = 0; root < edges.size(); ++root) {
    if (colors[root])
      continue;
    colors[root] = 1;
    stack.emplace_back(root, 0);
    while (!stack.empty()) {
      const std::size_t node = stack.back().first;
      std::size_t &next = stack.back().second;
      if (next == edges[node].size()) {
        colors[node] = 2;
        result.leavesFirst.push_back(node);
        stack.pop_back();
        continue;
      }
      const std::size_t target = edges[node][next++];
      if (target >= edges.size() || colors[target] == 1) {
        result.status = target >= edges.size() ? HierarchyOrder::Status::UnknownTarget
                                               : HierarchyOrder::Status::Cycle;
        result.source = node;
        result.target = target;
        result.leavesFirst.clear();
        return result;
      }
      if (!colors[target]) {
        colors[target] = 1;
        stack.emplace_back(target, 0);
      }
    }
  }
  return result;
}

} // namespace nodal

#endif // NODAL_SUPPORT_HIERARCHYORDER_H
