#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/ClockModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/APFloat.h"
#include "llvm/ADT/APInt.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/Error.h"

#include <algorithm>
#include <charconv>
#include <map>
#include <numeric>
#include <optional>
#include <set>
#include <string>
#include <vector>

using namespace mlir;
namespace nodal {
namespace {
constexpr unsigned arithmeticBits = 4096;
constexpr unsigned maximumWork = 65536;
StringRef text(Operation *op, StringRef key) {
  auto value = op ? op->getAttrOfType<StringAttr>(key) : StringAttr();
  return value ? value.getValue() : StringRef();
}
StringRef path(Operation *op) {
  auto metadata = op ? op->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto value = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
  return value ? value.getValue() : StringRef();
}
LogicalResult reject(Operation *op, StringRef message) {
  return op->emitOpError("NODAL-CLOCK-RELATION-057-001: ") << message;
}
using Integer = llvm::APInt;
Integer integer(uint64_t value = 0) { return Integer(arithmeticBits, value); }
Integer gcd(Integer a, Integer b) {
  while (!b.isZero()) {
    Integer remainder = a.urem(b);
    a = std::move(b);
    b = std::move(remainder);
  }
  return a;
}
std::optional<Integer> product(const Integer &a, const Integer &b) {
  // Bound the result BEFORE multiplying. No oversized APInt is allocated, even
  // transiently; the boundary division handles a possibly fitting final bit.
  if (!a.isZero() && !b.isZero() && a.getActiveBits() + b.getActiveBits() > arithmeticBits &&
      a.ugt(Integer::getAllOnes(arithmeticBits).udiv(b)))
    return {};
  return a * b;
}
struct Rational {
  Integer numerator = integer();
  Integer denominator = integer(1);
  bool negative = false;
  Rational() = default;
  explicit Rational(uint64_t value) : numerator(integer(value)) {}
  Rational(Integer n, Integer d, bool sign = false)
      : numerator(std::move(n)), denominator(std::move(d)), negative(sign) {
    Integer factor = gcd(numerator, denominator);
    numerator = numerator.udiv(factor);
    denominator = denominator.udiv(factor);
    if (numerator.isZero())
      negative = false;
  }
  bool operator==(const Rational &other) const {
    return negative == other.negative && numerator == other.numerator &&
           denominator == other.denominator;
  }
  Rational negated() const {
    Rational result = *this;
    if (!numerator.isZero())
      result.negative = !negative;
    return result;
  }
  Rational inverse() const { return Rational(denominator, numerator, negative); }
};
std::optional<Rational> multiply(const Rational &a, const Rational &b) {
  Integer left = gcd(a.numerator, b.denominator), right = gcd(b.numerator, a.denominator);
  auto n = product(a.numerator.udiv(left), b.numerator.udiv(right));
  auto d = product(a.denominator.udiv(right), b.denominator.udiv(left));
  if (!n || !d)
    return {};
  return Rational(*n, *d, a.negative != b.negative);
}
std::optional<Rational> add(const Rational &a, const Rational &b) {
  Integer common = gcd(a.denominator, b.denominator);
  auto left = product(a.numerator, b.denominator.udiv(common));
  auto right = product(b.numerator, a.denominator.udiv(common));
  auto denominator = product(a.denominator, b.denominator.udiv(common));
  if (!left || !right || !denominator)
    return {};
  if (a.negative != b.negative) {
    bool useLeft = left->uge(*right);
    return Rational(useLeft ? *left - *right : *right - *left, *denominator,
                    useLeft ? a.negative : b.negative);
  }
  bool overflow = false;
  Integer sum = left->uadd_ov(*right, overflow);
  return overflow ? std::optional<Rational>()
                  : std::optional<Rational>(Rational(sum, *denominator, a.negative));
}
// Java Double.toString's finite canonical decimal transport (JDK 19+):
// shortest round-trip, choosing the closest two-digit decimal when one digit
// would suffice, fixed notation for [1e-3, 1e7), and an explicit fractional
// digit for integral/scientific values. Arithmetic never uses this double.
std::optional<std::string> canonicalDouble(double value) {
  char buffer[64];
  auto converted =
      std::to_chars(buffer, buffer + sizeof(buffer), value, std::chars_format::scientific);
  if (converted.ec != std::errc())
    return {};
  StringRef result(buffer, converted.ptr - buffer);
  auto exponentAt = result.find('e');
  if (!result.take_front(exponentAt).contains('.')) {
    converted =
        std::to_chars(buffer, buffer + sizeof(buffer), value, std::chars_format::scientific, 1);
    if (converted.ec != std::errc())
      return {};
    result = StringRef(buffer, converted.ptr - buffer);
    exponentAt = result.find('e');
  }
  int exponent = 0;
  StringRef exponentText = result.drop_front(exponentAt + 1);
  exponentText.consume_front("+");
  if (exponentText.getAsInteger(10, exponent))
    return {};
  StringRef mantissa = result.take_front(exponentAt);
  bool negative = mantissa.consume_front("-");
  std::string digits;
  for (char digit : mantissa)
    if (digit != '.')
      digits += digit;
  while (digits.size() > 1 && digits.back() == '0')
    digits.pop_back();
  std::string formatted;
  if (exponent >= -3 && exponent < 7) {
    int point = exponent + 1;
    if (point <= 0)
      formatted = "0." + std::string(-point, '0') + digits;
    else if (unsigned(point) >= digits.size())
      formatted = digits + std::string(point - digits.size(), '0') + ".0";
    else
      formatted = digits.substr(0, point) + "." + digits.substr(point);
  } else {
    formatted = digits.substr(0, 1) + "." + (digits.size() == 1 ? "0" : digits.substr(1)) + "E" +
                std::to_string(exponent);
  }
  return (negative ? "-" : "") + formatted;
}
std::optional<Rational> decimal(StringRef spelling) {
  if (spelling.empty() || spelling.size() > 1400 || spelling.trim() != spelling)
    return {};
  llvm::APFloat finite(llvm::APFloat::IEEEdouble());
  auto converted = finite.convertFromString(spelling, llvm::APFloat::rmNearestTiesToEven);
  if (!converted) {
    llvm::consumeError(converted.takeError());
    return {};
  }
  if (!finite.isFinite())
    return {};
  auto canonical = canonicalDouble(finite.convertToDouble());
  if (!canonical || *canonical != spelling)
    return {};
  bool negative = spelling.consume_front("-");
  if (spelling.empty() || spelling.front() == '+')
    return {};
  Integer numerator = integer();
  unsigned digits = 0, decimals = 0;
  bool point = false;
  while (!spelling.empty() && spelling.front() != 'e' && spelling.front() != 'E') {
    char c = spelling.front();
    spelling = spelling.drop_front();
    if (c == '.' && !point) {
      point = true;
      continue;
    }
    if (c < '0' || c > '9')
      return {};
    auto scaled = product(numerator, integer(10));
    if (!scaled)
      return {};
    bool overflow = false;
    numerator = scaled->uadd_ov(integer(c - '0'), overflow);
    if (overflow)
      return {};
    ++digits;
    decimals += point;
  }
  if (!digits || (finite.isZero() && !numerator.isZero()))
    return {};
  int exponent = 0;
  if (!spelling.empty()) {
    spelling = spelling.drop_front();
    bool minus = spelling.consume_front("-");
    if (!minus)
      spelling.consume_front("+");
    if (spelling.empty() || spelling.size() > 4 || spelling.getAsInteger(10, exponent))
      return {};
    if (minus)
      exponent = -exponent;
  }
  exponent -= decimals;
  if (exponent < -1233 || exponent > 1233)
    return {};
  Integer denominator = integer(1);
  Integer *scaled = exponent < 0 ? &denominator : &numerator;
  for (int count = std::abs(exponent); count; --count) {
    auto next = product(*scaled, integer(10));
    if (!next)
      return {};
    *scaled = std::move(*next);
  }
  return Rational(numerator, denominator, negative);
}
std::optional<Rational> phaseTurns(StringRef spelling) {
  auto degrees = decimal(spelling);
  if (!degrees)
    return {};
  auto turns = multiply(*degrees, Rational(integer(1), integer(360)));
  if (!turns)
    return {};
  Integer remainder = turns->numerator.urem(turns->denominator);
  if (turns->negative && !remainder.isZero())
    remainder = turns->denominator - remainder;
  return Rational(remainder, turns->denominator);
}
struct RelationFields {
  StringRef kind;
  Rational period{1}; // Pdestination / Psource.
  Rational phase;     // Canonical [0, 1) turns of Pdestination.
  std::optional<bool> phaseKnown;
};
FailureOr<RelationFields> inspectFields(DictionaryAttr fields, Operation *diagnostic) {
  auto bad = [&](StringRef message) -> FailureOr<RelationFields> {
    (void)reject(diagnostic, message);
    return failure();
  };
  if (!fields)
    return bad("relationship intent requires its closed typed field dictionary");
  for (NamedAttribute attribute : fields)
    if (!llvm::is_contained(
            ArrayRef<StringRef>{"kind", "multiply", "divide", "phase", "phase_known"},
            attribute.getName().getValue()))
      return bad("relationship intent contains an unsupported field");
  RelationFields result;
  auto kind = fields.getAs<StringAttr>("kind");
  result.kind = kind ? kind.getValue() : StringRef();
  if (!llvm::is_contained(ArrayRef<StringRef>{"same", "ratio", "synchronous", "mutually_exclusive",
                                              "asynchronous", "unknown"},
                          result.kind))
    return bad("unsupported executable relationship kind");
  bool ratio = result.kind == "ratio", synchronous = result.kind == "synchronous";
  if ((!ratio && (fields.get("multiply") || fields.get("divide") || fields.get("phase"))) ||
      (!synchronous && fields.get("phase_known")))
    return bad("kind-specific relationship fields must be absent for every other kind");
  if (ratio) {
    auto m = fields.getAs<IntegerAttr>("multiply"), d = fields.getAs<IntegerAttr>("divide");
    auto p = fields.getAs<StringAttr>("phase");
    auto turns = p ? phaseTurns(p.getValue()) : std::optional<Rational>();
    if (!m || !d || !m.getType().isSignlessInteger(32) || !d.getType().isSignlessInteger(32) ||
        m.getInt() <= 0 || d.getInt() <= 0 || !turns)
      return bad("Ratio requires positive signed-32-bit integers and canonical finite exact "
                 "decimal phase (4096-bit arithmetic bound)");
    result.period = Rational(integer(d.getInt()), integer(m.getInt()));
    result.phase = *turns;
  } else if (synchronous) {
    auto known = fields.getAs<BoolAttr>("phase_known");
    if (!known)
      return bad("Synchronous requires exact boolean phase_known");
    result.phaseKnown = known.getValue();
  }
  return result;
}
struct Header : RelationFields {
  Operation *operation = nullptr;
  Operation *source = nullptr;
  Operation *destination = nullptr;
  ClockIdentity clocks[2];
};
FailureOr<Header> inspect(Operation *op, const DomainModel &domains) {
  auto bad = [&](StringRef message) -> FailureOr<Header> {
    (void)reject(op, message);
    return failure();
  };
  if (!isa_and_nonnull<ClockRelationshipOp>(op) || !isa<ModuleOp>(op->getParentOp()) ||
      op->getNumOperands() != 2 || op->getNumResults() || op->getNumRegions() ||
      op->getNumSuccessors())
    return bad("relationship requires exactly two actual flat owned Clock operands");
  for (NamedAttribute attribute : op->getAttrs())
    if (!llvm::is_contained(ArrayRef<StringRef>{"source", "destination", "kind", "contract",
                                                "proof", "multiply", "divide", "phase",
                                                "phase_known", "obligations", "metadata"},
                            attribute.getName().getValue()))
      return bad("relationship contains an unsupported field");
  StringRef owner = path(op->getParentOp());
  if (owner.empty())
    owner = text(op->getParentOp(), "sym_name");
  if (text(op, "contract") != "relationship_v1" || path(op).empty() || path(op).size() > 4096 ||
      path(op).trim() != path(op) || !path(op).starts_with((owner + ".").str()) ||
      path(op).size() <= owner.size() + 1)
    return bad("relationship requires its closed contract and owned semantic identity");
  Header result;
  result.operation = op;
  SmallVector<NamedAttribute> fieldAttributes;
  for (StringRef name : {"kind", "multiply", "divide", "phase", "phase_known"})
    if (auto value = op->getAttr(name))
      fieldAttributes.emplace_back(StringAttr::get(op->getContext(), name), value);
  auto fields = inspectFields(DictionaryAttr::get(op->getContext(), fieldAttributes), op);
  if (failed(fields))
    return failure();
  static_cast<RelationFields &>(result) = *fields;
  for (unsigned i = 0; i != 2; ++i) {
    Attribute reference = op->getAttr(i ? "destination" : "source");
    auto domain = domains.resolveDomain(reference);
    auto binding = domains.resolveClockBinding(reference);
    auto identity = resolveClock(op->getOperand(i), op);
    if (failed(domain) || failed(binding) || failed(identity) ||
        failed(verifyDigitalValueScope(op->getOperand(i), op)) ||
        !sameClock(op->getOperand(i), op, (*binding)->getOperand(0), *binding))
      return bad("actual Clock operand does not match its uniquely bound owned domain");
    (i ? result.destination : result.source) = *domain;
    result.clocks[i] = *identity;
  }
  if (result.source->getAttr("edge") != result.destination->getAttr("edge"))
    return bad("relationship endpoints require matching active-edge polarity");
  StringRef proof = text(op, "proof");
  auto obligations = op->getAttrOfType<DictionaryAttr>("obligations");
  auto obligation = [&](StringRef key, StringRef expected) {
    auto value = obligations ? obligations.getAs<StringAttr>(key) : StringAttr();
    return obligations && obligations.size() == 1 && value && value.getValue() == expected;
  };
  if (!obligations)
    return bad("relationship requires its exact retained obligations");
  if (result.kind == "same") {
    bool identical = result.clocks[0] == result.clocks[1];
    if (identical ? (proof != "structural" || !obligations.empty())
                  : (proof != "external_contract" || !result.clocks[0].physical ||
                     !result.clocks[1].physical || !obligation("active_edges", "identical_stream")))
      return bad("Same requires exact structural identity or two external inputs and their "
                 "identical active-edge environment contract; derived metadata is not proof");
  } else if (result.kind == "ratio") {
    if (proof != "timing_contract" || !obligation("timing", "exact_relative_period_phase"))
      return bad("Ratio requires its retained timing obligation");
  } else if (result.kind == "synchronous") {
    if (proof != "timing_contract" || !obligation("timing", "synchronous_phase_knowledge_retained"))
      return bad("Synchronous requires its retained timing knowledge");
  } else if (result.kind == "mutually_exclusive") {
    if (proof != "operating_mode_contract" ||
        !obligation("exclusion", "never_simultaneously_enabled"))
      return bad("MutuallyExclusive requires its operating-mode exclusion obligation");
  } else if (proof != "classification" || !obligations.empty()) {
    return bad("Asynchronous and Unknown require classification and no fabricated obligations");
  }
  return result;
}
struct UnionFind {
  std::vector<unsigned> parents;
  explicit UnionFind(unsigned count = 0) : parents(count) {
    std::iota(parents.begin(), parents.end(), 0);
  }
  unsigned representative(unsigned id) const {
    while (parents[id] != id)
      id = parents[id];
    return id;
  }
  unsigned root(unsigned id) {
    unsigned top = representative(id);
    while (id != top) {
      unsigned next = parents[id];
      parents[id] = top;
      id = next;
    }
    return top;
  }
  void join(unsigned a, unsigned b) {
    a = root(a);
    b = root(b);
    if (a != b)
      parents[std::max(a, b)] = std::min(a, b);
  }
};
struct TimingEdge {
  unsigned source, destination;
  Rational period{1}, phase;
  Operation *operation;
};
} // namespace

struct DomainRelationshipIndex {
  struct Coordinate {
    Rational period{1}, offset;
    bool assigned = false;
  };
  struct PairClaims {
    std::optional<bool> phaseKnown;
    bool exclusive = false;
  };
  bool valid = false, complete = false;
  llvm::DenseMap<Operation *, unsigned> domains;
  UnionFind same, timing, synchronous, physical;
  std::vector<Coordinate> coordinates;
  std::vector<std::optional<Rational>> componentFrequency;
  std::map<std::pair<unsigned, unsigned>, PairClaims> claims;
  std::set<std::pair<unsigned, unsigned>> asynchronousPairs;
  using Producer = std::pair<Operation *, std::string>;
  std::vector<Producer> producers;
  // Independent coordinate witnesses, one per producer timing component.
  // A valid physical producer has at most its rising/falling edge components.
  std::map<Producer, SmallVector<unsigned, 2>> frequencySources;
};

LogicalResult verifyBoundaryRelationIntentFields(DictionaryAttr intent, Operation *diagnostic) {
  return success(succeeded(inspectFields(intent, diagnostic)));
}

LogicalResult verifyClockRelationship(Operation *op) {
  return success(succeeded(inspect(op, DomainModel(op))));
}

LogicalResult DomainModel::verifyRelationships() const { return verifyRelationshipIndex(false); }

LogicalResult DomainModel::verifyRelationshipIndex(bool endpointFacts) const {
  if (relationships && (!endpointFacts || relationships->complete || !relationships->valid))
    return success(relationships->valid);
  relationships = std::make_shared<DomainRelationshipIndex>();
  auto &index = *relationships;
  if (!module)
    return failure();
  SmallVector<Operation *> records, domainList;
  unsigned work = 0;
  for (Operation &op : module->getRegion(0).front()) {
    if (++work > maximumWork)
      return reject(module, "relationship indexing exceeds finite operation budget");
    if (isa<ClockRelationshipOp>(op))
      records.push_back(&op);
    else if (isa<DomainOp>(op))
      domainList.push_back(&op);
  }
  if (records.empty() && !endpointFacts) {
    index.valid = true;
    return success();
  }
  // Stable source ordering, rather than declaration or hash iteration ordering,
  // selects one bounded spanning forest. No pairwise transitive closure exists.
  llvm::sort(domainList,
             [](Operation *a, Operation *b) { return text(a, "sym_name") < text(b, "sym_name"); });
  llvm::sort(records, [](Operation *a, Operation *b) { return path(a) < path(b); });
  unsigned count = domainList.size();
  index.same = UnionFind(count);
  index.timing = UnionFind(count);
  index.synchronous = UnionFind(count);
  index.physical = UnionFind(count);
  auto &timing = index.timing, &synchronous = index.synchronous, &physical = index.physical;
  for (unsigned i = 0; i != count; ++i)
    index.domains.try_emplace(domainList[i], i);
  SmallVector<TimingEdge> edges;
  std::map<std::tuple<Operation *, std::string, std::string>, unsigned> identities;
  std::vector<std::optional<Rational>> frequencies(count);
  index.producers.resize(count);
  std::map<std::pair<Operation *, std::string>, Rational> physicalFrequencies;
  for (unsigned i = 0; i != count; ++i) {
    Operation *domain = domainList[i];
    auto ref = FlatSymbolRefAttr::get(module->getContext(), text(domain, "sym_name"));
    Operation *binding = findUniqueLocalBinding(ref);
    if (!binding)
      continue;
    auto verified = resolveClockBinding(ref);
    if (failed(verified))
      return failure();
    auto clock = resolveClock(binding->getOperand(0), binding);
    if (failed(clock))
      return failure();
    Operation *owner = clock->physical ? clock->physical->port : clock->producer;
    std::string key = clock->physical ? clock->physical->scalarKey : "";
    index.producers[i] = {owner, key};
    auto inserted =
        identities.try_emplace(std::make_tuple(owner, key, text(domain, "edge").str()), i);
    if (!inserted.second) {
      unsigned alias = inserted.first->second;
      edges.push_back({alias, i, Rational(1), Rational(), domain});
      physical.join(alias, i);
    }
    auto metadata = domain->getAttrOfType<DictionaryAttr>("metadata");
    if (metadata && metadata.get("frequency_hz")) {
      auto spelling = metadata.getAs<StringAttr>("frequency_hz");
      auto frequency = spelling ? decimal(spelling.getValue()) : std::optional<Rational>();
      if (!frequency || frequency->negative || frequency->numerator.isZero())
        return reject(domain, "supplied frequency requires a positive finite exact decimal");
      auto physicalFrequency =
          physicalFrequencies.try_emplace(std::make_pair(owner, key), *frequency);
      if (!physicalFrequency.second && !(physicalFrequency.first->second == *frequency))
        return reject(domain,
                      "physical clock aliases have inconsistent frequencies across active edges");
      frequencies[i] = *frequency;
    }
  }
  SmallVector<Header, 0> headers;
  llvm::StringSet<> semanticPaths;
  for (Operation *op : records) {
    auto record = inspect(op, *this);
    if (failed(record))
      return failure();
    if (!semanticPaths.insert(path(op)).second)
      return reject(op, "relationships require distinct owned semantic identities");
    unsigned a = index.domains.lookup(record->source),
             b = index.domains.lookup(record->destination);
    if (record->kind == "same") {
      index.same.join(a, b);
      physical.join(a, b); // Consistency aliases, never extra sampling permission.
    }
    if (record->kind == "same" || record->kind == "ratio")
      edges.push_back({a, b, record->period, record->phase, op});
    if (record->kind == "same" || record->kind == "ratio" || record->kind == "synchronous")
      synchronous.join(a, b);
    headers.push_back(std::move(*record));
  }
  std::vector<SmallVector<std::pair<unsigned, bool>, 2>> adjacency(count);
  for (unsigned i = 0; i != edges.size(); ++i) {
    auto &edge = edges[i];
    adjacency[edge.source].push_back({i, false});
    adjacency[edge.destination].push_back({i, true});
    timing.join(edge.source, edge.destination);
    synchronous.join(edge.source, edge.destination);
  }
  for (unsigned node = 0; node != count; ++node)
    llvm::sort(adjacency[node], [&](auto left, auto right) {
      const auto &a = edges[left.first], &b = edges[right.first];
      unsigned na = left.second ? a.source : a.destination;
      unsigned nb = right.second ? b.source : b.destination;
      if (na != nb)
        return na < nb;
      return path(a.operation) < path(b.operation);
    });
  index.coordinates.resize(count);
  auto &coordinates = index.coordinates;
  for (unsigned root = 0; root != count; ++root) {
    if (coordinates[root].assigned)
      continue;
    coordinates[root].assigned = true;
    SmallVector<unsigned> pending{root};
    for (unsigned cursor = 0; cursor < pending.size(); ++cursor) {
      unsigned from = pending[cursor];
      for (auto [edgeIndex, reverse] : adjacency[from]) {
        if (++work > maximumWork)
          return reject(module, "relationship graph exceeds finite operation budget");
        const auto &edge = edges[edgeIndex];
        unsigned to = reverse ? edge.source : edge.destination;
        if (coordinates[to].assigned)
          continue;
        auto period =
            multiply(coordinates[from].period, reverse ? edge.period.inverse() : edge.period);
        auto delta =
            multiply(edge.phase, reverse ? coordinates[from].period : period.value_or(Rational()));
        auto offset = delta ? add(coordinates[from].offset, reverse ? delta->negated() : *delta)
                            : std::optional<Rational>();
        if (!period || !offset)
          return reject(edge.operation, "exact relationship arithmetic exceeds 4096-bit bound");
        coordinates[to] = {*period, *offset, true};
        pending.push_back(to);
      }
    }
  }
  for (const auto &edge : edges) {
    const auto &a = coordinates[edge.source], &b = coordinates[edge.destination];
    auto expectedPeriod = multiply(a.period, edge.period);
    auto delta = multiply(edge.phase, b.period);
    auto offset = delta ? add(a.offset, *delta) : std::optional<Rational>();
    auto residual = offset ? add(b.offset, offset->negated()) : std::optional<Rational>();
    auto turns = residual ? multiply(*residual, b.period.inverse()) : std::optional<Rational>();
    if (!expectedPeriod || !turns)
      return reject(edge.operation, "exact relationship arithmetic exceeds 4096-bit bound");
    if (!(*expectedPeriod == b.period) || !turns->denominator.isOne())
      return reject(edge.operation, "inconsistent exact ratio/phase path, physical alias or cycle");
  }
  index.componentFrequency.resize(count);
  auto &componentFrequency = index.componentFrequency;
  for (unsigned i = 0; i != count; ++i) {
    if (!frequencies[i])
      continue;
    auto rootFrequency = multiply(*frequencies[i], coordinates[i].period);
    if (!rootFrequency)
      return reject(domainList[i], "frequency consistency exceeds 4096-bit arithmetic bound");
    unsigned root = timing.root(i);
    if (componentFrequency[root] && !(*componentFrequency[root] == *rootFrequency))
      return reject(domainList[i],
                    "supplied frequency contradicts exact clock relationships or aliases");
    componentFrequency[root] = *rootFrequency;
  }
  for (unsigned i = 0; i != count; ++i) {
    if (!componentFrequency[timing.representative(i)] || !index.producers[i].first)
      continue;
    auto &sources = index.frequencySources[index.producers[i]];
    if (llvm::none_of(sources, [&](unsigned known) {
          return timing.representative(known) == timing.representative(i);
        }))
      sources.push_back(i);
  }
  auto &claims = index.claims;
  for (const auto &header : headers) {
    unsigned a = index.domains.lookup(header.source), b = index.domains.lookup(header.destination);
    unsigned pa = physical.root(a), pb = physical.root(b);
    auto &pair = claims[{std::min(pa, pb), std::max(pa, pb)}];
    if (header.kind == "asynchronous") {
      unsigned sa = synchronous.representative(a), sb = synchronous.representative(b);
      index.asynchronousPairs.insert({std::min(sa, sb), std::max(sa, sb)});
    }
    pair.exclusive |= header.kind == "mutually_exclusive";
    if (header.kind == "asynchronous" && synchronous.root(a) == synchronous.root(b))
      return reject(header.operation, "Asynchronous contradicts known synchronous or Same timing");
    if (header.kind == "mutually_exclusive") {
      if (index.same.root(a) == index.same.root(b) || physical.root(a) == physical.root(b))
        return reject(header.operation,
                      "operating-mode exclusion contradicts Same or physical identity");
    } else if (header.kind == "synchronous") {
      if (pair.phaseKnown && pair.phaseKnown != header.phaseKnown)
        return reject(header.operation, "contradictory duplicate phase_known claims");
      pair.phaseKnown = header.phaseKnown;
    }
  }
  index.complete = true;
  index.valid = true;
  return success();
}

namespace {
// Both admission and boundary queries use the same bounded exact coordinates.
// A query reads representatives without path compression and records no claims.
LogicalResult verifyBoundaryFacts(const DomainRelationshipIndex &left, unsigned source,
                                  const DomainRelationshipIndex &right, unsigned destination,
                                  const RelationFields &intent, bool sameProducer, bool sameEdge,
                                  Operation *diagnostic) {
  bool local = &left == &right;
  bool identical = sameProducer && sameEdge;
  if (intent.kind == "same" && !sameEdge)
    return reject(diagnostic, "Same boundary intent contradicts different active edges");
  const DomainRelationshipIndex::PairClaims *known = nullptr;
  if (local) {
    unsigned a = left.physical.representative(source),
             b = left.physical.representative(destination);
    auto found = left.claims.find({std::min(a, b), std::max(a, b)});
    if (found != left.claims.end())
      known = &found->second;
  }
  auto frequency = [&](const DomainRelationshipIndex &index,
                       unsigned node) -> FailureOr<std::optional<Rational>> {
    std::optional<Rational> result;
    auto sources = index.frequencySources.find(index.producers[node]);
    if (sources == index.frequencySources.end())
      return result;
    for (unsigned source : sources->second) {
      const auto &root = index.componentFrequency[index.timing.representative(source)];
      auto value = multiply(*root, index.coordinates[source].period.inverse());
      if (!value) {
        (void)reject(diagnostic, "boundary frequency comparison exceeds 4096-bit arithmetic bound");
        return failure();
      }
      if (result && !(*result == *value)) {
        (void)reject(diagnostic,
                     "physical clock has inconsistent independently inferred frequencies");
        return failure();
      }
      result = *value;
    }
    return result;
  };
  auto sourceFrequency = frequency(left, source),
       destinationFrequency = frequency(right, destination);
  if (failed(sourceFrequency) || failed(destinationFrequency))
    return failure();
  bool exact = intent.kind == "same" || intent.kind == "ratio";
  if ((exact || intent.kind == "synchronous") && local) {
    unsigned a = left.synchronous.representative(source),
             b = left.synchronous.representative(destination);
    if (left.asynchronousPairs.count({std::min(a, b), std::max(a, b)}))
      return reject(diagnostic,
                    "boundary intent contradicts independently known asynchronous classification");
  }
  if (intent.kind == "same" && known && known->exclusive)
    return reject(diagnostic, "Same boundary intent contradicts independently known exclusion");
  if (intent.kind == "asynchronous" &&
      (sameProducer || (local && left.synchronous.representative(source) ==
                                     left.synchronous.representative(destination))))
    return reject(diagnostic,
                  "asynchronous boundary intent contradicts independently known timing or Same");
  if (intent.kind == "mutually_exclusive" &&
      (sameProducer ||
       (local &&
        (left.same.representative(source) == left.same.representative(destination) ||
         left.physical.representative(source) == left.physical.representative(destination)))))
    return reject(diagnostic, "exclusive boundary intent contradicts physical identity or Same");
  if (intent.kind == "synchronous" && known && known->phaseKnown &&
      known->phaseKnown != intent.phaseKnown)
    return reject(diagnostic,
                  "boundary intent contradicts independently known synchronous phase knowledge");
  if (exact && sameProducer && !(intent.period == Rational(1)))
    return reject(diagnostic, "boundary intent contradicts the frequency of one physical clock");
  if (exact && identical && !intent.phase.numerator.isZero())
    return reject(diagnostic, "boundary intent contradicts exact physical clock phase");
  if (exact && local &&
      left.timing.representative(source) == left.timing.representative(destination)) {
    const auto &a = left.coordinates[source], &b = left.coordinates[destination];
    auto expected = multiply(a.period, intent.period);
    auto delta = expected ? multiply(*expected, intent.phase) : std::optional<Rational>();
    auto offset = delta ? add(a.offset, *delta) : std::optional<Rational>();
    auto residual = offset ? add(b.offset, offset->negated()) : std::optional<Rational>();
    auto turns =
        residual && expected ? multiply(*residual, expected->inverse()) : std::optional<Rational>();
    if (!expected || !turns)
      return reject(diagnostic, "boundary intent exceeds 4096-bit exact arithmetic bound");
    if (!(*expected == b.period) || !turns->denominator.isOne())
      return reject(diagnostic,
                    "boundary intent contradicts independently known exact period or phase");
  } else if (exact || sameProducer) {
    if (*sourceFrequency && *destinationFrequency) {
      auto expected = multiply(**destinationFrequency, exact ? intent.period : Rational(1));
      if (!expected)
        return reject(diagnostic,
                      "boundary frequency comparison exceeds 4096-bit arithmetic bound");
      if (!(**sourceFrequency == *expected))
        return reject(diagnostic, "boundary intent contradicts independently known frequencies");
    }
  }
  return success();
}
} // namespace

LogicalResult DomainModel::verifyBoundaryRelationIntent(Attribute source, Attribute destination,
                                                        DictionaryAttr intent,
                                                        Operation *diagnostic) const {
  auto fields = inspectFields(intent, diagnostic);
  if (failed(fields))
    return failure();
  auto a = resolveDomain(source), b = resolveDomain(destination);
  auto sourceBinding = resolveClockBinding(source),
       destinationBinding = resolveClockBinding(destination);
  if (failed(a) || failed(b) || failed(sourceBinding) || failed(destinationBinding))
    return reject(diagnostic, "boundary intent requires exact owned bound domain endpoints");
  auto sourceClock = resolveClock((*sourceBinding)->getOperand(0), *sourceBinding);
  auto destinationClock = resolveClock((*destinationBinding)->getOperand(0), *destinationBinding);
  if (failed(sourceClock) || failed(destinationClock))
    return reject(diagnostic, "boundary intent requires independently resolved Clock identity");
  if (failed(verifyRelationshipIndex(true)))
    return failure();
  auto left = relationships->domains.find(*a), right = relationships->domains.find(*b);
  if (left == relationships->domains.end() || right == relationships->domains.end())
    return reject(diagnostic,
                  "boundary intent endpoints are absent from the owned relationship index");
  return verifyBoundaryFacts(*relationships, left->second, *relationships, right->second, *fields,
                             *sourceClock == *destinationClock,
                             (*a)->getAttr("edge") == (*b)->getAttr("edge"), diagnostic);
}

LogicalResult verifyBoundaryRelationIntent(Attribute source, Attribute destination,
                                           DictionaryAttr intent, Operation *diagnostic,
                                           const HierarchyModel &hierarchy, unsigned occurrence) {
  auto a = hierarchy.resolveDomain(occurrence, source),
       b = hierarchy.resolveDomain(occurrence, destination);
  auto leftBinding = hierarchy.resolveBinding(occurrence, source),
       rightBinding = hierarchy.resolveBinding(occurrence, destination);
  if (failed(a) || failed(b) || failed(leftBinding) || failed(rightBinding))
    return reject(diagnostic,
                  "boundary intent requires exact occurrence-owned bound domain endpoints");
  auto reference = [](Operation *op) {
    return FlatSymbolRefAttr::get(op->getContext(), text(op, "sym_name"));
  };
  DomainModel left(hierarchy.occurrences()[a->occurrence].module);
  if (a->occurrence == b->occurrence)
    return left.verifyBoundaryRelationIntent(reference(a->declaration), reference(b->declaration),
                                             intent, diagnostic);
  auto fields = inspectFields(intent, diagnostic);
  if (failed(fields))
    return failure();
  auto leftClock = resolveClock(leftBinding->clock, hierarchy),
       rightClock = resolveClock(rightBinding->clock, hierarchy);
  if (failed(leftClock) || failed(rightClock))
    return reject(diagnostic,
                  "boundary intent requires independently resolved contextual Clock identity");
  DomainModel right(hierarchy.occurrences()[b->occurrence].module);
  if (failed(left.verifyRelationshipIndex(true)) || failed(right.verifyRelationshipIndex(true)))
    return failure();
  auto li = left.relationships->domains.find(a->declaration),
       ri = right.relationships->domains.find(b->declaration);
  if (li == left.relationships->domains.end() || ri == right.relationships->domains.end())
    return reject(
        diagnostic,
        "boundary intent endpoints are absent from their occurrence relationship indices");
  return verifyBoundaryFacts(*left.relationships, li->second, *right.relationships, ri->second,
                             *fields, *leftClock == *rightClock,
                             a->declaration->getAttr("edge") == b->declaration->getAttr("edge"),
                             diagnostic);
}

bool DomainModel::allowsDirectSampling(Attribute reference, Attribute expected) const {
  if (failed(verifyRelationships()))
    return false;
  if (matchesActualDomain(reference, expected))
    return true;
  auto from = resolveDomain(reference), to = resolveDomain(expected);
  if (failed(from) || failed(to))
    return false;
  // Keep the existing receiving-domain contract: expected is an actual domain,
  // not a requirement selected arbitrarily from an equivalence component.
  if (FlatSymbolRefAttr::get(module->getContext(), text(*to, "sym_name")) != expected)
    return false;
  auto a = relationships->domains.find(*from), b = relationships->domains.find(*to);
  return a != relationships->domains.end() && b != relationships->domains.end() &&
         relationships->same.root(a->second) == relationships->same.root(b->second);
}
} // namespace nodal
