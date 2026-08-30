#ifndef NODAL_ANALOG_EQUATION_RUNTIME_H
#define NODAL_ANALOG_EQUATION_RUNTIME_H

#include <algorithm>
#include <map>
#include <optional>
#include <set>
#include <stdexcept>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

namespace nodal::analog {

enum class RegionKind { Equation, InitialEquation, Contribution, Procedural };
enum class ValueKind { Real, Boolean };
enum class ContributionKind { Potential, Flow };

struct SourceSpan {
  std::string file;
  int line = 0;
  int column = 0;
};

struct Expression {
  std::string rendered;
  std::string dimension;
  ValueKind valueKind = ValueKind::Real;
};

struct Metadata {
  std::string owner;
  std::optional<Expression> guard;
  std::set<std::string> analyses;
  std::string continuity;
  SourceSpan source;
};

struct ContributionTarget {
  std::string identity;
  ContributionKind kind = ContributionKind::Potential;
  std::string dimension;
  std::string orientation;

  friend bool operator<(const ContributionTarget &left,
                        const ContributionTarget &right) {
    return std::tie(left.identity, left.kind, left.dimension,
                    left.orientation) <
           std::tie(right.identity, right.kind, right.dimension,
                    right.orientation);
  }
};

struct ResidualIntent {
  Expression authoredLeft;
  Expression authoredRight;
  std::string canonicalConvention = "lhs-minus-rhs-equals-zero";
  bool causallyOriented = false;
  bool divided = false;
};

struct EquationRecord {
  std::string identity;
  bool initialOnly = false;
  Metadata metadata;
  ResidualIntent residual;
};

struct ContributionRecord {
  std::string identity;
  ContributionTarget target;
  Expression value;
  Metadata metadata;
};

struct ContributionBucket {
  ContributionTarget target;
  std::vector<ContributionRecord> terms;
};

struct Snapshot {
  std::vector<EquationRecord> equations;
  std::vector<ContributionBucket> contributions;
};

class SemanticError final : public std::runtime_error {
public:
  SemanticError(std::string code, std::string message)
      : std::runtime_error(std::move(message)), code_(std::move(code)) {}

  [[nodiscard]] const std::string &code() const noexcept { return code_; }

private:
  std::string code_;
};

class Recorder final {
public:
  class Region final {
  public:
    Region(Recorder &owner, RegionKind kind) : owner_(&owner) {
      owner_->enter(kind);
    }
    Region(const Region &) = delete;
    Region &operator=(const Region &) = delete;
    Region(Region &&other) noexcept
        : owner_(std::exchange(other.owner_, nullptr)) {}
    Region &operator=(Region &&) = delete;
    ~Region() {
      if (owner_ != nullptr) {
        owner_->leave();
      }
    }

  private:
    Recorder *owner_;
  };

  [[nodiscard]] Region region(RegionKind kind) { return Region(*this, kind); }

  const EquationRecord &recordEquation(std::string identity, Expression left,
                                       Expression right, Metadata metadata) {
    const bool initialOnly = active_ == RegionKind::InitialEquation;
    if (active_ != RegionKind::Equation && !initialOnly) {
      if (active_.has_value()) {
        fail("NODAL-ANALOG-032-002",
             "unordered equations are illegal in contribution and procedural regions");
      }
      fail("NODAL-ANALOG-032-003", "equation requires an equation region");
    }
    if (equations_.contains(identity)) {
      fail("NODAL-ANALOG-032-004", "duplicate equation identity: " + identity);
    }
    if (left.valueKind != ValueKind::Real ||
        right.valueKind != ValueKind::Real) {
      fail("NODAL-ANALOG-032-005",
           "equation operands must be real-valued expressions");
    }
    if (left.dimension != right.dimension) {
      fail("NODAL-ANALOG-032-006", "equation dimensions differ");
    }
    validateMetadata(metadata);
    EquationRecord record{identity,
                          initialOnly,
                          std::move(metadata),
                          ResidualIntent{std::move(left), std::move(right)}};
    return equations_.emplace(record.identity, std::move(record)).first->second;
  }

  const ContributionRecord &
  recordContribution(std::string identity, ContributionTarget target,
                     Expression value, Metadata metadata) {
    if (active_ != RegionKind::Contribution) {
      if (active_.has_value()) {
        fail("NODAL-ANALOG-032-012",
             "additive contributions are legal only in a contribution region");
      }
      fail("NODAL-ANALOG-032-013",
           "contribution requires a contribution region");
    }
    if (contributions_.contains(identity)) {
      fail("NODAL-ANALOG-032-009",
           "duplicate contribution identity: " + identity);
    }
    if (value.valueKind != ValueKind::Real) {
      fail("NODAL-ANALOG-032-010",
           "a potential/flow contribution must be real-valued");
    }
    if (value.dimension != target.dimension) {
      fail("NODAL-ANALOG-032-011",
           "contribution dimension does not match target dimension");
    }
    validateMetadata(metadata);
    ContributionRecord record{identity, std::move(target), std::move(value),
                              std::move(metadata)};
    return contributions_.emplace(record.identity, std::move(record))
        .first->second;
  }

  [[nodiscard]] Snapshot snapshot() const {
    Snapshot result;
    result.equations.reserve(equations_.size());
    for (const auto &[identity, equation] : equations_) {
      (void)identity;
      result.equations.push_back(equation);
    }

    std::map<ContributionTarget, std::vector<ContributionRecord>> grouped;
    for (const auto &[identity, contribution] : contributions_) {
      (void)identity;
      grouped[contribution.target].push_back(contribution);
    }
    for (auto &[target, terms] : grouped) {
      std::sort(terms.begin(), terms.end(),
                [](const ContributionRecord &left,
                   const ContributionRecord &right) {
                  return left.identity < right.identity;
                });
      result.contributions.push_back(
          ContributionBucket{std::move(target), std::move(terms)});
    }
    return result;
  }

private:
  std::optional<RegionKind> active_;
  std::map<std::string, EquationRecord> equations_;
  std::map<std::string, ContributionRecord> contributions_;

  [[noreturn]] static void fail(std::string code, std::string message) {
    throw SemanticError(std::move(code), std::move(message));
  }

  void enter(RegionKind kind) {
    if (active_.has_value()) {
      fail("NODAL-ANALOG-032-001", "analog semantic regions cannot overlap");
    }
    active_ = kind;
  }

  void leave() { active_.reset(); }

  static void validateMetadata(const Metadata &metadata) {
    if (metadata.owner.empty() || metadata.analyses.empty() ||
        metadata.continuity.empty() || metadata.source.file.empty() ||
        metadata.source.line < 1 || metadata.source.column < 1) {
      fail("NODAL-ANALOG-032-014", "semantic metadata is incomplete");
    }
    if (metadata.guard.has_value() &&
        (metadata.guard->valueKind != ValueKind::Boolean ||
         metadata.guard->dimension != "1")) {
      fail("NODAL-ANALOG-032-007",
           "an equation or contribution guard must be dimensionless Boolean");
    }
  }
};

} // namespace nodal::analog

#endif // NODAL_ANALOG_EQUATION_RUNTIME_H
