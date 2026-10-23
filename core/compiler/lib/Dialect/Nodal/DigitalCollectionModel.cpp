#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"

#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"
#include "nodal/Support/BackendCapabilityRegistry.h"

#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringSet.h"

#include <algorithm>
#include <functional>
#include <limits>

using namespace mlir;
namespace nodal {
namespace {
constexpr unsigned maximumRegionDepth = 128;
constexpr unsigned maximumRegionNodes = 65536;
constexpr unsigned maximumArguments = 4098;
constexpr unsigned maximumAnalysisWork = 65536;
using Expr = InstantiatedIntegerExpression;

bool named(Operation *operation, StringRef name) {
  return operation && operation->getName().getStringRef() == name;
}
StringRef text(Operation *operation, StringRef name) {
  auto attribute = operation ? operation->getAttrOfType<StringAttr>(name) : StringAttr();
  return attribute ? attribute.getValue() : StringRef();
}
StringRef path(Operation *operation) {
  auto metadata =
      operation ? operation->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto value = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
  return value ? value.getValue() : StringRef();
}
Operation *owner(Operation *operation) {
  if (!operation)
    return nullptr;
  return llvm::isa<ModuleOp>(operation) ? operation : operation->getParentOfType<ModuleOp>();
}
LogicalResult reject(Operation *operation, StringRef message) {
  return operation->emitOpError("NODAL-DIGITAL-055-001: ") << message;
}
bool isReduction(StringRef kind) { return kind == "reduce_left" || kind == "reduce_balanced"; }
StringRef kind(Type type) {
  if (llvm::isa<BitsType>(type))
    return "bits";
  if (llvm::isa<UIntType>(type))
    return "uint";
  if (llvm::isa<SIntType>(type))
    return "sint";
  if (auto scalar = llvm::dyn_cast<ParametricIntegerType>(type))
    return scalar.getKind();
  return {};
}
bool literalBool(Type type) {
  auto bits = llvm::dyn_cast<BitsType>(type);
  return bits && bits.getWidth() == 1;
}
struct Slot {
  Type initialType;
  BlockArgument left, right;
  Type yieldedType;
};
struct Signature {
  Operation *operation = nullptr;
  Operation *module = nullptr;
  Block *body = nullptr;
  unsigned inputs = 0, seeds = 0, captures = 0, ordinaryArguments = 0, slots = 0;
  ShapedType inputType;
  StringRef kind;
  SmallVector<Slot, 4> selected;
};

/// Pure raw header walk. It never resolves a width, visits a value opcode or
/// invokes another semantic verifier. Complete type validity is a later phase.
FailureOr<Signature> signature(Operation *operation) {
  Signature h;
  if (!named(operation, "nodal.collection") || operation->getNumRegions() != 1 ||
      operation->getNumResults() != 1 || operation->getNumSuccessors() ||
      operation->getNumOperands() > maximumArguments || operation->hasAttr("unit") ||
      !operation->getAttrOfType<DictionaryAttr>("metadata"))
    return failure();
  h.operation = operation;
  h.module = owner(operation);
  Operation *parent = operation->getParentOp();
  if (!h.module || (!llvm::isa_and_nonnull<ModuleOp>(parent) && !isDigitalRegionOwner(parent)))
    return failure();
  unsigned depth = 0, reductions = isReduction(text(operation, "kind")) ? 1 : 0;
  for (Operation *scope = parent; named(scope, "nodal.collection"); scope = scope->getParentOp()) {
    if (++depth >= maximumRegionDepth)
      return failure();
    reductions += isReduction(text(scope, "kind"));
  }
  if (reductions > 1)
    return failure();
  auto segments = operation->getAttrOfType<DenseI32ArrayAttr>("operandSegmentSizes");
  if (!segments || segments.size() != 3)
    return failure();
  auto sizes = segments.asArrayRef();
  uint64_t total = 0;
  for (int32_t size : sizes) {
    if (size < 0)
      return failure();
    total += static_cast<uint64_t>(size);
  }
  if (total != operation->getNumOperands())
    return failure();
  h.kind = text(operation, "kind");
  bool folded = h.kind == "fold_left" || h.kind == "scan_left";
  if (h.kind != "map" && h.kind != "zip_with" && !folded && !isReduction(h.kind))
    return failure();
  h.inputs = h.kind == "zip_with" ? 2 : 1;
  h.seeds = folded ? 1 : 0;
  h.captures = sizes[2];
  if (sizes[0] != static_cast<int32_t>(h.inputs) || sizes[1] != static_cast<int32_t>(h.seeds))
    return failure();
  Region &region = operation->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();
  h.body = &region.front();
  unsigned arguments = h.body->getNumArguments();
  if (arguments > maximumArguments)
    return failure();
  if (isReduction(h.kind)) {
    if (arguments < h.captures + 2 || (arguments - h.captures - 2) % 2)
      return failure();
    h.slots = (arguments - h.captures - 2) / 2;
    h.ordinaryArguments = 2 * h.slots + 2;
    for (unsigned i = 0; i < 2 * h.slots; ++i)
      if (!h.body->getArgument(i).getType().isSignlessInteger(64))
        return failure();
  } else {
    h.ordinaryArguments = folded ? 2 : h.inputs;
    if (arguments != h.ordinaryArguments + h.captures)
      return failure();
  }
  h.inputType = llvm::dyn_cast<ShapedType>(operation->getOperand(0).getType());
  if (!h.inputType)
    return failure();
  for (unsigned i = 0; i < h.inputs; ++i)
    if (!llvm::isa<ShapedType>(operation->getOperand(i).getType()))
      return failure();
  StringRef id = text(operation, "region_id"), moduleId = path(h.module);
  if (moduleId.empty())
    moduleId = text(h.module, "sym_name");
  std::string prefix = (moduleId + ".").str();
  if (id.empty() || id.size() > digitalTypeMaximumIdentifierBytes || id != id.trim() ||
      id.contains(' ') || id.contains('\t') || id.contains('\n') || id.contains('\r') ||
      !id.starts_with(prefix) || id.size() <= prefix.size() || path(operation) != id)
    return failure();
  return h;
}

class TemplateWalker {
public:
  explicit TemplateWalker(Signature &header) : h(header) {}
  bool walk(Type input, Type left, Type right, unsigned depth = 0) {
    if (depth >= digitalTypeMaximumDepth || ++work > maximumAnalysisWork)
      return false;
    StringRef k = kind(input);
    if (!k.empty()) {
      if (kind(left) != k || kind(right) != k)
        return false;
      if (literalBool(input) && literalBool(left) && literalBool(right))
        return true;
      if (h.selected.size() >= h.slots)
        return false;
      auto l = llvm::dyn_cast<ParametricIntegerType>(left),
           r = llvm::dyn_cast<ParametricIntegerType>(right);
      unsigned i = h.selected.size();
      BlockArgument la = h.body->getArgument(i), ra = h.body->getArgument(h.slots + i);
      if (!l || !r || l.getWidth() != digitalValueSemanticPath(la) ||
          r.getWidth() != digitalValueSemanticPath(ra))
        return false;
      h.selected.push_back({input, la, ra, {}});
      return true;
    }
    if (auto a = llvm::dyn_cast<ShapedType>(input)) {
      auto b = llvm::dyn_cast<ShapedType>(left), c = llvm::dyn_cast<ShapedType>(right);
      if (!b || !c)
        return false;
      SmallVector<StringRef, 4> x, y, z;
      a.getDimensions().split(x, ',');
      b.getDimensions().split(y, ',');
      c.getDimensions().split(z, ',');
      return x.size() == y.size() && x.size() == z.size() &&
             walk(a.getElementType(), b.getElementType(), c.getElementType(), depth + 1);
    }
    if (auto a = llvm::dyn_cast<StructType>(input)) {
      auto b = llvm::dyn_cast<StructType>(left), c = llvm::dyn_cast<StructType>(right);
      if (!b || !c || a.getName() != b.getName() || a.getName() != c.getName() ||
          a.getFields().size() != b.getFields().size() ||
          a.getFields().size() != c.getFields().size())
        return false;
      for (unsigned i = 0; i < a.getFields().size(); ++i) {
        auto x = llvm::dyn_cast<DictionaryAttr>(a.getFields()[i]),
             y = llvm::dyn_cast<DictionaryAttr>(b.getFields()[i]),
             z = llvm::dyn_cast<DictionaryAttr>(c.getFields()[i]);
        if (!x || !y || !z || x.getAs<StringAttr>("name") != y.getAs<StringAttr>("name") ||
            x.getAs<StringAttr>("name") != z.getAs<StringAttr>("name") ||
            !x.getAs<TypeAttr>("type") || !y.getAs<TypeAttr>("type") ||
            !z.getAs<TypeAttr>("type") ||
            !walk(x.getAs<TypeAttr>("type").getValue(), y.getAs<TypeAttr>("type").getValue(),
                  z.getAs<TypeAttr>("type").getValue(), depth + 1))
          return false;
      }
      return true;
    }
    return input == left && input == right && isDigitalStorageType(input);
  }

private:
  Signature &h;
  unsigned work = 0;
};
FailureOr<Signature> inspect(Operation *operation) {
  auto h = signature(operation);
  if (failed(h))
    return failure();
  if (isReduction(h->kind)) {
    TemplateWalker walker(*h);
    if (!walker.walk(h->inputType.getElementType(), h->body->getArgument(2 * h->slots).getType(),
                     h->body->getArgument(2 * h->slots + 1).getType()) ||
        h->selected.size() != h->slots)
      return failure();
  }
  return h;
}

FailureOr<Expr> tokenExpression(Operation *module, StringRef token, StringRef effect,
                                const ParameterIntegerEnvironment *env) {
  if (env)
    return resolveCollectionIntegerToken(module, token, effect, *env);
  auto dimension = resolveStaticShapeDimension(module, token, effect);
  if (failed(dimension))
    return failure();
  if (dimension->parameter)
    return resolveOwnedIntegerParameter(module, dimension->parameter,
                                        effect == "width"
                                            ? ParameterIntegerMode::MathematicalSigned64
                                            : ParameterIntegerMode::Storage);
  if (dimension->expression)
    return resolveOwnedIntegerExpression(module, dimension->expression,
                                         effect == "width"
                                             ? ParameterIntegerMode::MathematicalSigned64
                                             : ParameterIntegerMode::Storage);
  return makeLiteralIntegerExpression(dimension->literal);
}
FailureOr<Expr> shapeCount(const Signature &h, const ParameterIntegerEnvironment *enclosing) {
  Expr count = makeLiteralIntegerExpression(1);
  SmallVector<StringRef, 4> tokens;
  h.inputType.getDimensions().split(tokens, ',');
  if (tokens.size() > maximumArguments)
    return failure();
  for (StringRef token : tokens) {
    auto extent = tokenExpression(h.module, token, "shape", enclosing);
    if (failed(extent) || extent->bounds.lower <= 0)
      return failure();
    auto next = composeIntegerExpression("mul", {count, *extent});
    if (failed(next))
      return failure();
    count = *next;
  }
  return count;
}

struct Profile {
  enum Kind { Invariant, Fixed, MaxGrowth, SumGrowth } kind;
  Expr initial, coefficient;
};
struct Candidate {
  Signature header;
  Expr count;
  SmallVector<Profile, 4> profiles;
  SmallVector<ParameterIntegerEnvironment, 4> contexts;
  std::shared_ptr<const ParameterIntegerEnvironment> enclosing;
};
} // namespace

namespace detail {
/// The single production admission owner. Callers cannot supply serialized
/// intervals/roles: header mapping and every capture ordinal are recovered here.
class CollectionIntegerEnvironmentAdmission {
public:
  static FailureOr<ParameterIntegerEnvironment>
  create(const Signature &h, Operation *use, ArrayRef<Expr> exact,
         ArrayRef<std::optional<ParameterIntegerBounds>> domains,
         const ParameterIntegerEnvironment *enclosing) {
    if (!use || use->getBlock() != h.body || exact.size() != 2 * h.slots ||
        domains.size() != exact.size() || exact.size() + h.captures > maximumArguments)
      return failure();
    SmallVector<ParameterIntegerBinding, 8> bindings;
    SmallVector<ParameterIntegerFormalDomain, 8> formalDomains;
    for (unsigned i = 0; i < exact.size(); ++i) {
      auto argument = h.body->getArgument(i);
      if (domains[i])
        formalDomains.push_back({argument, *domains[i]});
      else {
        if (!exact[i].root)
          return failure();
        bindings.push_back({argument, exact[i], {}});
      }
    }
    for (unsigned i = 0; i < h.captures; ++i) {
      auto argument = h.body->getArgument(h.ordinaryArguments + i);
      Value source = h.operation->getOperand(h.inputs + h.seeds + i);
      if (argument.getType().isSignlessInteger(64)) {
        if (!source.getType().isSignlessInteger(64) ||
            failed(verifyCollectionValueScope(source, h.operation)))
          return failure();
        FailureOr<Expr> expression =
            enclosing ? resolveOwnedIntegerExpression(h.module, source, *enclosing,
                                                      ParameterIntegerMode::MathematicalSigned64)
                      : resolveDigitalLoopStaticInteger(source, h.operation);
        if (failed(expression)) {
          // A real Generate capture remains available to predicates/selectors,
          // including an empty template. It never becomes a width formal.
          if (failed(resolveStructuralIntegerExpression(source, h.operation)))
            return failure();
        } else {
          bindings.push_back({argument, *expression, source});
        }
      } else if (source.getType().isSignlessInteger(64))
        return failure();
    }
    return ParameterIntegerEnvironment::create(h.module, h.body, use,
                                               enclosing ? enclosing->instance() : nullptr,
                                               bindings, formalDomains, enclosing);
  }
};
} // namespace detail

namespace {
FailureOr<ParameterIntegerEnvironment> exactContext(const Signature &h, ArrayRef<Expr> left,
                                                    ArrayRef<Expr> right,
                                                    const ParameterIntegerEnvironment *enclosing,
                                                    Operation *use = nullptr) {
  if (h.body->empty() || left.size() != h.slots || right.size() != h.slots)
    return failure();
  SmallVector<Expr, 8> values;
  values.append(left.begin(), left.end());
  values.append(right.begin(), right.end());
  SmallVector<std::optional<ParameterIntegerBounds>, 8> domains(values.size());
  return detail::CollectionIntegerEnvironmentAdmission::create(h, use ? use : &h.body->back(),
                                                               values, domains, enclosing);
}

FailureOr<Expr> profileWidth(const Profile &p, const Expr &size, bool balanced) {
  if (size.bounds.lower < 1)
    return failure();
  if (p.kind == Profile::Invariant)
    return p.initial;
  auto previous = composeIntegerExpression("sub", {size, makeLiteralIntegerExpression(1)});
  if (failed(previous))
    return failure();
  if (p.kind == Profile::Fixed)
    return composeIntegerExpression("choose_zero", {*previous, p.initial, p.coefficient});
  auto factor = balanced && p.kind == Profile::MaxGrowth
                    ? composeIntegerExpression("ceil_log2", {size})
                    : previous;
  if (failed(factor))
    return failure();
  auto growth = composeIntegerExpression("mul", {*factor, p.coefficient});
  if (failed(growth))
    return failure();
  auto initial = p.kind == Profile::SumGrowth ? composeIntegerExpression("mul", {size, p.initial})
                                              : FailureOr<Expr>(p.initial);
  if (failed(initial))
    return failure();
  return composeIntegerExpression("add", {*initial, *growth});
}

/// Raw recurrence inference inspects real SSA before any query substitution.
/// Ordinary ParameterModel resolves only terms proved independent of all formals.
class TransferAnalysis {
public:
  TransferAnalysis(const Signature &h, const ParameterIntegerEnvironment &environment)
      : h(h), environment(environment) {
    for (unsigned i = 0; i < 2 * h.slots; ++i)
      formals.insert(h.body->getArgument(i));
  }
  FailureOr<Profile> infer(Type yielded, const Slot &slot, const Expr &initial) {
    if (kind(yielded) != kind(slot.initialType))
      return failure();
    Profile p{Profile::Fixed, initial, {}};
    auto symbolic = llvm::dyn_cast<ParametricIntegerType>(yielded);
    if (!symbolic) {
      auto width = inferDigitalWidthExpression(yielded, h.module, &environment);
      if (failed(width))
        return failure();
      p.coefficient = *width;
      return p;
    }
    auto raw = lookupDigitalRegionToken(h.body, symbolic.getWidth());
    if (failed(raw))
      return failure();
    if (!*raw) {
      auto width = tokenExpression(h.module, symbolic.getWidth(), "width", &environment);
      if (failed(width))
        return failure();
      p.coefficient = *width;
      return p;
    }
    if (*raw == slot.left || *raw == slot.right) {
      p.kind = Profile::Invariant;
      p.coefficient = makeLiteralIntegerExpression(0);
      return p;
    }
    SmallVector<Value, 8> terms;
    if (!flatten(*raw, terms, 0))
      return failure();
    unsigned left = 0, right = 0, maximum = 0;
    Expr coefficient = makeLiteralIntegerExpression(0);
    for (Value term : terms) {
      if (term == slot.left) {
        ++left;
        continue;
      }
      if (term == slot.right) {
        ++right;
        continue;
      }
      Operation *op = term.getDefiningOp();
      if (op && named(op, "nodal.const_expr") && text(op, "operator_name") == "max" &&
          op->getNumOperands() == 2 &&
          ((op->getOperand(0) == slot.left && op->getOperand(1) == slot.right) ||
           (op->getOperand(0) == slot.right && op->getOperand(1) == slot.left))) {
        ++maximum;
        continue;
      }
      auto dependent = depends(term, 0);
      if (failed(dependent) || *dependent)
        return failure();
      auto value = resolveOwnedIntegerExpression(h.module, term, environment,
                                                 ParameterIntegerMode::MathematicalSigned64);
      if (failed(value))
        return failure();
      auto next = composeIntegerExpression("add", {coefficient, *value});
      if (failed(next))
        return failure();
      coefficient = *next;
    }
    if (left == 0 && right == 0 && maximum == 0) {
      p.kind = Profile::Fixed;
      p.coefficient = coefficient;
      if (coefficient.bounds.lower <= 0)
        return failure();
      return p;
    }
    if (coefficient.bounds.lower < 0)
      return failure();
    if (left == 1 && right == 1 && maximum == 0)
      p.kind = Profile::SumGrowth;
    else if (left == 0 && right == 0 && maximum == 1)
      p.kind = Profile::MaxGrowth;
    else
      return failure();
    p.coefficient = coefficient;
    return p;
  }

private:
  bool flatten(Value value, SmallVectorImpl<Value> &terms, unsigned depth) {
    if (depth >= 512 || ++work > maximumAnalysisWork || !active.insert(value).second)
      return false;
    Operation *op = value.getDefiningOp();
    bool valid = true;
    if (op && named(op, "nodal.const_expr") && text(op, "operator_name") == "add" &&
        op->getNumOperands() == 2)
      valid = flatten(op->getOperand(0), terms, depth + 1) &&
              flatten(op->getOperand(1), terms, depth + 1);
    else
      terms.push_back(value);
    active.erase(value);
    return valid;
  }
  FailureOr<bool> depends(Value value, unsigned depth) {
    if (depth >= 512 || ++work > maximumAnalysisWork || !active.insert(value).second)
      return failure();
    if (formals.contains(value)) {
      active.erase(value);
      return true;
    }
    if (auto arg = llvm::dyn_cast<BlockArgument>(value)) {
      active.erase(value);
      return arg.getOwner() == h.body ? FailureOr<bool>(false) : FailureOr<bool>(failure());
    }
    auto *op = value.getDefiningOp();
    if (!op || op->getNumResults() != 1 || !value.getType().isSignlessInteger(64) ||
        (op->getParentOp() != h.module && op->getBlock() != h.body) ||
        (!named(op, "nodal.const_expr") && !named(op, "nodal.const_literal") &&
         !named(op, "nodal.const_parameter_ref")))
      return failure();
    bool any = false;
    for (Value operand : op->getOperands()) {
      auto dependency = depends(operand, depth + 1);
      if (failed(dependency))
        return failure();
      any |= *dependency;
    }
    active.erase(value);
    return any;
  }
  const Signature &h;
  const ParameterIntegerEnvironment &environment;
  unsigned work = 0;
  llvm::DenseSet<Value> formals, active;
};

/// Paired template/yield walk retains exact invariant leaves and inner dimensions.
/// Width-family membership is deliberately insufficient for invariant Bits<1>.
bool yieldedSlots(Type input, Type left, Type right, Type yielded, Signature &h,
                  const ParameterIntegerEnvironment &env, const ParameterIntegerEnvironment *outer,
                  unsigned &slot, unsigned &work, unsigned depth = 0) {
  if (depth >= digitalTypeMaximumDepth || ++work > maximumAnalysisWork)
    return false;
  if (!kind(input).empty()) {
    if (kind(input) != kind(yielded))
      return false;
    if (literalBool(input) && literalBool(left) && literalBool(right))
      return literalBool(yielded);
    if (slot >= h.selected.size())
      return false;
    h.selected[slot++].yieldedType = yielded;
    return true;
  }
  if (auto a = llvm::dyn_cast<ShapedType>(input)) {
    auto b = llvm::dyn_cast<ShapedType>(left), c = llvm::dyn_cast<ShapedType>(right),
         d = llvm::dyn_cast<ShapedType>(yielded);
    if (!b || !c || !d)
      return false;
    SmallVector<StringRef, 4> x, y, z, w;
    a.getDimensions().split(x, ',');
    b.getDimensions().split(y, ',');
    c.getDimensions().split(z, ',');
    d.getDimensions().split(w, ',');
    if (x.size() != y.size() || x.size() != z.size() || x.size() != w.size())
      return false;
    for (unsigned i = 0; i < x.size(); ++i) {
      auto initial = tokenExpression(h.module, x[i], "shape", outer);
      if (failed(initial))
        return false;
      for (StringRef token : {y[i], z[i], w[i]}) {
        auto other = tokenExpression(h.module, token, "shape", &env);
        if (failed(other) || !equivalentInstantiatedIntegerExpressions(*initial, *other))
          return false;
      }
    }
    return yieldedSlots(a.getElementType(), b.getElementType(), c.getElementType(),
                        d.getElementType(), h, env, outer, slot, work, depth + 1);
  }
  if (auto a = llvm::dyn_cast<StructType>(input)) {
    auto b = llvm::dyn_cast<StructType>(left), c = llvm::dyn_cast<StructType>(right),
         d = llvm::dyn_cast<StructType>(yielded);
    if (!b || !c || !d || a.getName() != d.getName() ||
        a.getFields().size() != d.getFields().size())
      return false;
    for (unsigned i = 0; i < a.getFields().size(); ++i) {
      auto x = llvm::cast<DictionaryAttr>(a.getFields()[i]),
           y = llvm::cast<DictionaryAttr>(b.getFields()[i]),
           z = llvm::cast<DictionaryAttr>(c.getFields()[i]),
           w = llvm::dyn_cast<DictionaryAttr>(d.getFields()[i]);
      if (!w || x.getAs<StringAttr>("name") != w.getAs<StringAttr>("name") ||
          !w.getAs<TypeAttr>("type") ||
          !yieldedSlots(x.getAs<TypeAttr>("type").getValue(), y.getAs<TypeAttr>("type").getValue(),
                        z.getAs<TypeAttr>("type").getValue(), w.getAs<TypeAttr>("type").getValue(),
                        h, env, outer, slot, work, depth + 1))
        return false;
    }
    return true;
  }
  return input == yielded;
}

FailureOr<std::pair<int64_t, int64_t>> partialRange(int64_t lower, int64_t upper, int64_t half) {
  using Wide = __int128_t;
  Wide period = Wide(half) * 2, lo = lower, hi = upper;
  Wide firstCycle = lo / period, lastCycle = hi / period;
  Wide first = std::max(lo, firstCycle * period + half + 1);
  if (first > firstCycle * period + period - 1) {
    ++firstCycle;
    first = firstCycle * period + half + 1;
  }
  Wide last = std::min(hi, lastCycle * period + period - 1);
  if (last < lastCycle * period + half + 1) {
    --lastCycle;
    last = lastCycle * period + period - 1;
  }
  if (first > hi || last < lo || first > last)
    return failure();
  int64_t a = static_cast<int64_t>(first - firstCycle * period - half),
          b = static_cast<int64_t>(last - lastCycle * period - half);
  if (firstCycle < lastCycle) {
    a = 1;
    b = half - 1;
  }
  return std::make_pair(a, b);
}

FailureOr<unsigned> operationProofCost(Operation *operation) {
  unsigned work = 1 + operation->getNumOperands();
  llvm::DenseSet<Type> types;
  llvm::DenseSet<Attribute> attributes;
  std::function<bool(Type, unsigned)> visitType;
  std::function<bool(Attribute, unsigned)> visitAttribute;
  visitType = [&](Type type, unsigned depth) {
    if (depth >= digitalTypeMaximumDepth || work >= maximumAnalysisWork)
      return false;
    if (!types.insert(type).second)
      return true;
    ++work;
    bool valid = true;
    type.walkImmediateSubElements([&](Attribute a) { valid &= visitAttribute(a, depth + 1); },
                                  [&](Type t) { valid &= visitType(t, depth + 1); });
    return valid;
  };
  visitAttribute = [&](Attribute attribute, unsigned depth) {
    if (depth >= digitalTypeMaximumDepth || work >= maximumAnalysisWork)
      return false;
    if (!attributes.insert(attribute).second)
      return true;
    ++work;
    bool valid = true;
    attribute.walkImmediateSubElements([&](Attribute a) { valid &= visitAttribute(a, depth + 1); },
                                       [&](Type t) { valid &= visitType(t, depth + 1); });
    return valid;
  };
  for (Type type : operation->getOperandTypes())
    if (!visitType(type, 0))
      return failure();
  for (Type type : operation->getResultTypes())
    if (!visitType(type, 0))
      return failure();
  // Source locations, naming intent and bridge provenance have an independent
  // bounded traversal below; they do not create new width/type obligations per
  // context. Keep every semantic metadata key (including path and enum ABI)
  // charged, as well as every ordinary schema/width/capture attribute.
  for (NamedAttribute attribute : operation->getAttrs()) {
    if (attribute.getName() != "metadata") {
      if (!visitAttribute(attribute.getValue(), 0))
        return failure();
      continue;
    }
    auto metadata = llvm::dyn_cast<DictionaryAttr>(attribute.getValue());
    if (!metadata)
      return failure();
    for (NamedAttribute entry : metadata) {
      StringRef key = entry.getName().strref();
      if (key.starts_with("source_") || key == "bridge_schema" || key == "bridge_version" ||
          key == "digital_source")
        continue;
      if (!visitAttribute(entry.getValue(), 0))
        return failure();
    }
  }
  return work;
}

// Inert source/provenance metadata has its own bounded traversal, once per
// candidate, rather than being multiplied by the number of universal contexts.
LogicalResult preflightMetadata(const Signature &h) {
  llvm::DenseSet<Attribute> attributes;
  llvm::DenseSet<Type> types;
  unsigned work = 0;
  std::function<bool(Attribute, unsigned)> checkAttribute;
  std::function<bool(Type, unsigned)> checkType;
  checkAttribute = [&](Attribute attribute, unsigned depth) {
    if (depth >= digitalTypeMaximumDepth || ++work > maximumAnalysisWork)
      return false;
    if (!attributes.insert(attribute).second)
      return true;
    bool valid = true;
    attribute.walkImmediateSubElements([&](Attribute a) { valid &= checkAttribute(a, depth + 1); },
                                       [&](Type t) { valid &= checkType(t, depth + 1); });
    return valid;
  };
  checkType = [&](Type type, unsigned depth) {
    if (depth >= digitalTypeMaximumDepth || ++work > maximumAnalysisWork)
      return false;
    if (!types.insert(type).second)
      return true;
    bool valid = true;
    type.walkImmediateSubElements([&](Attribute a) { valid &= checkAttribute(a, depth + 1); },
                                  [&](Type t) { valid &= checkType(t, depth + 1); });
    return valid;
  };
  for (Operation &op : *h.body)
    if (Attribute metadata = op.getAttr("metadata"))
      if (!checkAttribute(metadata, 0))
        return failure();
  return success();
}

LogicalResult deriveContexts(Candidate &candidate) {
  auto &h = candidate.header;
  bool balanced = h.kind == "reduce_balanced";
  const ParameterIntegerEnvironment *enclosing = candidate.enclosing.get();
  uint64_t bodyNodes = 2 * h.slots + 1;
  for (Operation &op : *h.body) {
    auto cost = operationProofCost(&op);
    if (failed(cost) || bodyNodes + *cost > maximumAnalysisWork)
      return failure();
    bodyNodes += *cost;
  }
  auto add = [&](int64_t leftMin, int64_t leftMax, int64_t rightMin,
                 int64_t rightMax) -> LogicalResult {
    if (bodyNodes * (candidate.contexts.size() + 1) > maximumAnalysisWork)
      return failure();
    SmallVector<Expr, 8> values(2 * h.slots);
    SmallVector<std::optional<ParameterIntegerBounds>, 8> domains(2 * h.slots);
    for (unsigned side = 0; side < 2; ++side)
      for (unsigned i = 0; i < h.slots; ++i) {
        int64_t lo = side ? rightMin : leftMin, hi = side ? rightMax : leftMax;
        auto a = profileWidth(candidate.profiles[i], makeLiteralIntegerExpression(lo), balanced);
        auto b = profileWidth(candidate.profiles[i], makeLiteralIntegerExpression(hi), balanced);
        if (failed(a) || failed(b) || a->bounds.lower <= 0 || b->bounds.upper < a->bounds.lower)
          return failure();
        unsigned index = side * h.slots + i;
        if (lo == hi || equivalentInstantiatedIntegerExpressions(*a, *b))
          values[index] = *a;
        else
          domains[index] = ParameterIntegerBounds{a->bounds.lower, b->bounds.upper};
      }
    auto context = detail::CollectionIntegerEnvironmentAdmission::create(h, &h.body->back(), values,
                                                                         domains, enclosing);
    if (failed(context))
      return failure();
    candidate.contexts.push_back(*context);
    return success();
  };
  int64_t n = candidate.count.bounds.upper;
  if (n < 2)
    return success();
  if (!balanced) {
    if (failed(add(1, 1, 1, 1)))
      return failure();
    return n >= 3 ? add(2, n - 1, 1, 1) : success();
  }
  for (unsigned level = 0; level <= 62; ++level) {
    int64_t b = static_cast<int64_t>(uint64_t(1) << level);
    using Wide = __int128_t;
    if (Wide(n) >= Wide(b) * 2 && failed(add(b, b, b, b)))
      return failure();
    auto partial = partialRange(candidate.count.bounds.lower, n, b);
    if (succeeded(partial)) {
      if (partial->first <= 1 && partial->second >= 1 && failed(add(b, b, 1, 1)))
        return failure();
      if (partial->second >= 2 &&
          failed(add(b, b, std::max<int64_t>(2, partial->first), partial->second)))
        return failure();
    }
    if (n <= b)
      break;
  }
  return success();
}

FailureOr<Candidate> candidate(Operation *operation, const ParameterIntegerEnvironment *enclosing) {
  auto h = inspect(operation);
  if (failed(h) || h->body->empty() || failed(preflightMetadata(*h)))
    return failure();
  Candidate c;
  c.header = *h;
  if (enclosing)
    c.enclosing = std::make_shared<const ParameterIntegerEnvironment>(*enclosing);
  auto count = shapeCount(*h, enclosing);
  if (failed(count) || count->bounds.lower <= 0)
    return failure();
  c.count = *count;
  SmallVector<Expr, 4> initial;
  for (const Slot &slot : h->selected) {
    auto width = inferDigitalWidthExpression(slot.initialType, h->module, enclosing);
    if (failed(width))
      return failure();
    initial.push_back(*width);
  }
  auto first = exactContext(*h, initial, initial, enclosing);
  if (failed(first))
    return failure();
  if (!isReduction(h->kind)) {
    c.contexts.push_back(*first);
    return c;
  }
  auto *yield = &h->body->back();
  if (!named(yield, "nodal.collection_yield") || yield->getNumOperands() != 1)
    return failure();
  unsigned slot = 0, work = 0;
  if (!yieldedSlots(h->inputType.getElementType(), h->body->getArgument(2 * h->slots).getType(),
                    h->body->getArgument(2 * h->slots + 1).getType(),
                    yield->getOperand(0).getType(), c.header, *first, enclosing, slot, work) ||
      slot != h->slots)
    return failure();
  TransferAnalysis transfer(c.header, *first);
  for (unsigned i = 0; i < h->slots; ++i) {
    auto profile =
        transfer.infer(c.header.selected[i].yieldedType, c.header.selected[i], initial[i]);
    if (failed(profile))
      return failure();
    c.profiles.push_back(*profile);
  }
  if (failed(deriveContexts(c)))
    return failure();
  return c;
}

bool equivalentAcross(Type a, const ParameterIntegerEnvironment *ea, Type b,
                      const ParameterIntegerEnvironment *eb, Operation *module, unsigned &work,
                      unsigned depth = 0) {
  if (depth >= digitalTypeMaximumDepth || ++work > maximumAnalysisWork)
    return false;
  return equivalentDigitalStorageTypesAcross(a, ea, b, eb, module);
}

bool expectedType(Type input, Type left, Type right, Type actual, ArrayRef<Expr> widths,
                  const Signature &h, const ParameterIntegerEnvironment *outer, unsigned &slot,
                  unsigned &work, int64_t &bits, unsigned depth = 0) {
  if (depth >= digitalTypeMaximumDepth || ++work > maximumAnalysisWork)
    return false;
  if (!kind(input).empty()) {
    if (kind(input) != kind(actual))
      return false;
    if (literalBool(input) && literalBool(left) && literalBool(right)) {
      bits = 1;
      return literalBool(actual);
    }
    if (slot >= widths.size())
      return false;
    const Expr &expected = widths[slot++];
    auto value = inferDigitalWidthExpression(actual, h.module, outer);
    if (failed(value) || expected.bounds.lower <= 0 ||
        !equivalentInstantiatedIntegerExpressions(expected, *value))
      return false;
    bits = expected.bounds.upper;
    return true;
  }
  if (auto a = llvm::dyn_cast<ShapedType>(input)) {
    auto b = llvm::dyn_cast<ShapedType>(left), c = llvm::dyn_cast<ShapedType>(right),
         d = llvm::dyn_cast<ShapedType>(actual);
    if (!b || !c || !d)
      return false;
    SmallVector<StringRef, 4> x, y;
    a.getDimensions().split(x, ',');
    d.getDimensions().split(y, ',');
    if (x.size() != y.size())
      return false;
    int64_t count = 1;
    for (unsigned i = 0; i < x.size(); ++i) {
      auto initial = tokenExpression(h.module, x[i], "shape", outer),
           value = tokenExpression(h.module, y[i], "shape", outer);
      if (failed(initial) || failed(value) ||
          !equivalentInstantiatedIntegerExpressions(*initial, *value) ||
          initial->bounds.lower <= 0 ||
          count > std::numeric_limits<int64_t>::max() / initial->bounds.upper)
        return false;
      count *= initial->bounds.upper;
    }
    if (!expectedType(a.getElementType(), b.getElementType(), c.getElementType(),
                      d.getElementType(), widths, h, outer, slot, work, bits, depth + 1) ||
        bits > std::numeric_limits<int64_t>::max() / count)
      return false;
    bits *= count;
    return true;
  }
  if (auto a = llvm::dyn_cast<StructType>(input)) {
    auto b = llvm::dyn_cast<StructType>(left), c = llvm::dyn_cast<StructType>(right),
         d = llvm::dyn_cast<StructType>(actual);
    if (!b || !c || !d || a.getName() != d.getName() ||
        a.getFields().size() != d.getFields().size())
      return false;
    bits = 0;
    for (unsigned i = 0; i < a.getFields().size(); ++i) {
      auto x = llvm::cast<DictionaryAttr>(a.getFields()[i]),
           y = llvm::cast<DictionaryAttr>(b.getFields()[i]),
           z = llvm::cast<DictionaryAttr>(c.getFields()[i]),
           w = llvm::cast<DictionaryAttr>(d.getFields()[i]);
      int64_t child = 0;
      if (x.getAs<StringAttr>("name") != w.getAs<StringAttr>("name") ||
          !expectedType(x.getAs<TypeAttr>("type").getValue(), y.getAs<TypeAttr>("type").getValue(),
                        z.getAs<TypeAttr>("type").getValue(), w.getAs<TypeAttr>("type").getValue(),
                        widths, h, outer, slot, work, child, depth + 1) ||
          child > std::numeric_limits<int64_t>::max() - bits)
        return false;
      bits += child;
    }
    return true;
  }
  if (input != actual)
    return false;
  auto size = inferDigitalStorageMaximumBits(input, h.operation, outer);
  if (failed(size))
    return false;
  bits = *size;
  return true;
}

FailureOr<DigitalReductionType> typeForSize(const Candidate &c, const Expr &size) {
  DigitalReductionType result;
  result.skeleton = c.header.inputType.getElementType();
  for (const Profile &profile : c.profiles) {
    auto width = profileWidth(profile, size, c.header.kind == "reduce_balanced");
    if (failed(width) || width->bounds.lower <= 0)
      return failure();
    result.widths.push_back(*width);
  }
  // Compute the complete dependent footprint without fabricating MLIR type tokens.
  unsigned cursor = 0, work = 0;
  std::function<FailureOr<int64_t>(Type, Type, Type, unsigned)> footprint =
      [&](Type a, Type l, Type r, unsigned depth) -> FailureOr<int64_t> {
    if (depth >= digitalTypeMaximumDepth || ++work > maximumAnalysisWork)
      return failure();
    if (!kind(a).empty()) {
      if (literalBool(a) && literalBool(l) && literalBool(r))
        return int64_t(1);
      if (cursor >= result.widths.size())
        return failure();
      return result.widths[cursor++].bounds.upper;
    }
    if (auto shape = llvm::dyn_cast<ShapedType>(a)) {
      auto x = llvm::cast<ShapedType>(l), y = llvm::cast<ShapedType>(r);
      auto child =
          footprint(shape.getElementType(), x.getElementType(), y.getElementType(), depth + 1);
      if (failed(child))
        return failure();
      SmallVector<StringRef, 4> tokens;
      shape.getDimensions().split(tokens, ',');
      int64_t total = *child;
      for (StringRef token : tokens) {
        auto extent = tokenExpression(c.header.module, token, "shape", c.enclosing.get());
        if (failed(extent) || extent->bounds.lower <= 0 ||
            total > std::numeric_limits<int64_t>::max() / extent->bounds.upper)
          return failure();
        total *= extent->bounds.upper;
        result.dimensions.push_back(*extent);
      }
      return total;
    }
    if (auto structure = llvm::dyn_cast<StructType>(a)) {
      auto x = llvm::cast<StructType>(l), y = llvm::cast<StructType>(r);
      int64_t total = 0;
      for (unsigned i = 0; i < structure.getFields().size(); ++i) {
        auto field = [](Attribute f) {
          return llvm::cast<DictionaryAttr>(f).getAs<TypeAttr>("type").getValue();
        };
        auto child = footprint(field(structure.getFields()[i]), field(x.getFields()[i]),
                               field(y.getFields()[i]), depth + 1);
        if (failed(child) || *child > std::numeric_limits<int64_t>::max() - total)
          return failure();
        total += *child;
      }
      return total;
    }
    return inferDigitalStorageMaximumBits(a, c.header.operation, c.enclosing.get());
  };
  auto bits = footprint(result.skeleton, c.header.body->getArgument(2 * c.header.slots).getType(),
                        c.header.body->getArgument(2 * c.header.slots + 1).getType(), 0);
  if (failed(bits) || cursor != result.widths.size())
    return failure();
  result.maximumBits = *bits;
  return result;
}

class CaptureBoundaryVerifier {
public:
  explicit CaptureBoundaryVerifier(const ParameterIntegerEnvironment *environment)
      : environment(environment) {}
  bool verify(Value value, unsigned depth = 0, bool staticSelector = false) {
    if (depth > maximumRegionDepth || ++work > maximumRegionNodes)
      return false;
    Region *region = value.getParentRegion();
    if (!region)
      return false;
    if (!isDigitalCombinationalValueType(value.getType(), owner(region->getParentOp()),
                                         environment) &&
        !(staticSelector && (value.getType().isInteger(64) || value.getType().isIndex())))
      return false;
    if (finished.contains(value))
      return true;
    if (!active.insert(value).second)
      return false;
    if (Operation *definition = value.getDefiningOp()) {
      auto *descriptor = lookupBackendOperation(definition->getName().getStringRef());
      if (!descriptor || isAnalogBackendOperation(descriptor->operation) ||
          definition->hasAttr("unit"))
        return false;
      if (llvm::isa<StructuralIfOp>(definition)) {
        if (failed(verifyStructuralIfOperation(definition, environment)))
          return false;
        active.erase(value);
        finished.insert(value);
        return true;
      }
      bool projection =
          named(definition, "nodal.shape_index") || named(definition, "nodal.shape_slice");
      // A collection's private operands are checked by its own body coordinator.
      // Its complete public result remains a dependency on all actual inputs.
      for (auto [index, operand] : llvm::enumerate(definition->getOperands()))
        if (failed(verifyCollectionValueScope(operand, definition)) ||
            !verify(operand, depth + 1, staticSelector || (projection && index > 0)))
          return false;
    } else if (!llvm::isa<BlockArgument>(value))
      return false;
    active.erase(value);
    finished.insert(value);
    return true;
  }

private:
  const ParameterIntegerEnvironment *environment;
  unsigned work = 0;
  llvm::DenseSet<Value> active, finished;
};
struct VerificationBudget {
  unsigned work = 0;
  llvm::StringSet<> identities;
};
LogicalResult verifyCandidate(Candidate &c, VerificationBudget &budget);

LogicalResult verifyBodyOperation(Operation *operation,
                                  const ParameterIntegerEnvironment &environment,
                                  VerificationBudget &budget) {
  auto cost = operationProofCost(operation);
  if (failed(cost) || *cost > maximumAnalysisWork - budget.work)
    return reject(operation,
                  "collection proof exceeds the 65,536 node-context analysis-work limit");
  budget.work += *cost;
  auto at = environment.atUse(operation);
  if (failed(at))
    return failure();
  if (operation->hasAttr("unit"))
    return reject(operation, "collection body must not introduce analog units");
  if (failed(verifyDigitalTypeOperation(operation, &*at)) ||
      failed(verifyEnumTypeOperation(operation, &*at)))
    return failure();
  if (named(operation, "nodal.collection")) {
    auto child = candidate(operation, &*at);
    if (failed(child))
      return reject(operation,
                    "invalid nested collection header, captures or finite reduction profile");
    return verifyCandidate(*child, budget);
  }
  if (llvm::isa<StructuralIfOp>(operation)) {
    if (operation->getNumResults() != 1)
      return reject(operation, "collection requires a pure value conditional");
    return verifyStructuralIfOperation(operation, &*at);
  }
  if (llvm::isa<StaticCompareOp, StaticLogicOp>(operation))
    return verifyStaticPredicateOperation(operation);
  if (named(operation, "nodal.collection_yield"))
    return verifyDigitalCollectionYield(operation);
  if (operation->getNumRegions() || operation->getNumSuccessors() ||
      operation->getNumResults() != 1 || !operation->getAttrOfType<DictionaryAttr>("metadata"))
    return reject(operation, "collection body requires a registered pure single-result value");
  for (Value operand : operation->getOperands())
    if (failed(verifyCollectionValueScope(operand, operation)))
      return reject(operation, "body operands must use local SSA or explicit captures");
  StringRef name = operation->getName().getStringRef();
  if (name != "nodal.constant" && name != "nodal.const_literal" && name != "nodal.const_expr" &&
      name != "nodal.const_parameter_ref" &&
      !isDigitalCombinationalValueType(operation->getResult(0).getType(), environment.module(),
                                       &*at))
    return reject(operation,
                  "collection intermediates must retain directionless digital value types");
  if (name == "nodal.digital_expr")
    return verifyDigitalValueOperation(operation, &*at);
  if (name == "nodal.enum_expr")
    return verifyEnumValueOperation(operation, &*at);
  if (name == "nodal.const_literal" || name == "nodal.const_expr" ||
      name == "nodal.const_parameter_ref") {
    Value result = operation->getResult(0);
    if (isDigitalScalarType(result.getType()) && name == "nodal.const_literal")
      return verifyDigitalLiteralOperation(operation, &*at);
    if (name == "nodal.const_literal" &&
        (result.getType().isIndex() ||
         isDigitalCombinationalValueType(result.getType(), environment.module(), &*at)))
      return llvm::cast<ConstLiteralOp>(operation).verify();
    if (!result.getType().isSignlessInteger(64))
      return reject(operation, "local static producer must be dimensionless i64");
    Operation *next = operation->getNextNode();
    auto after = next ? environment.atUse(next) : FailureOr<ParameterIntegerEnvironment>(failure());
    if (failed(after) ||
        (failed(resolveOwnedIntegerExpression(environment.module(), result, *after,
                                              ParameterIntegerMode::MathematicalSigned64)) &&
         failed(resolveStructuralIntegerExpression(result, next))))
      return reject(operation,
                    "static body expression is invalid over a reachable reduction context");
    if (name == "nodal.const_literal")
      return llvm::cast<ConstLiteralOp>(operation).verify();
    if (name == "nodal.const_parameter_ref")
      return llvm::cast<ConstParameterRefOp>(operation).verify();
    return llvm::cast<ConstExprOp>(operation).verify();
  }
  if (name == "nodal.constant") {
    Type type = operation->getResult(0).getType();
    if (isDigitalScalarType(type))
      return verifyDigitalLiteralOperation(operation, &*at);
    if (type.isInteger(64) || type.isIndex() ||
        isDigitalCombinationalValueType(type, environment.module(), &*at))
      return llvm::cast<ConstantOp>(operation).verify();
    return reject(operation, "collection literal must be digital data or a static selector");
  }
  if (name == "nodal.shape_index" && operation->getNumOperands() >= 2)
    return verifyShapeIndexMapping(operation, &*at);
  if (name == "nodal.shape_view" && operation->getNumOperands() == 1 &&
      text(operation, "materialization") == "view")
    return verifyShapeViewMapping(operation, &*at);
  if (name == "nodal.shape_slice" && operation->getNumOperands() == 4)
    return verifyShapeSliceMapping(operation, &*at);
  return reject(operation, "operation is outside the closed pure digital collection profile");
}

LogicalResult verifyCandidate(Candidate &c, VerificationBudget &budget) {
  auto &h = c.header;
  const auto *outer = c.enclosing.get();
  if (!budget.identities.insert(text(h.operation, "region_id")).second) {
    // The same lexical child is checked once per distinct parent context.
    // Module-wide uniqueness is checked separately from context work.
  }
  CaptureBoundaryVerifier boundary(outer);
  for (unsigned i = 0; i < h.operation->getNumOperands(); ++i) {
    Value value = h.operation->getOperand(i);
    if (failed(verifyCollectionValueScope(value, h.operation)))
      return reject(h.operation, "collection operands must dominate the actual lexical use");
    if (i >= h.inputs + h.seeds && value.getType().isSignlessInteger(64)) {
      auto expression =
          outer ? resolveOwnedIntegerExpression(h.module, value, *outer,
                                                ParameterIntegerMode::MathematicalSigned64)
                : resolveDigitalLoopStaticInteger(value, h.operation);
      if (failed(expression) && failed(resolveStructuralIntegerExpression(value, h.operation)))
        return reject(h.operation, "static capture is not an owned static Integer");
    } else if (!boundary.verify(value))
      return reject(h.operation,
                    "collection data operands must retain the digital capability boundary");
  }
  if (failed(verifyDigitalCollectionYield(&h.body->back())))
    return failure();
  // Validate the typed header using the exact first-call substitution even if the
  // count is a singleton. This does not execute or certify a callback occurrence.
  SmallVector<Expr, 4> initial;
  for (const auto &p : c.profiles)
    initial.push_back(p.initial);
  auto first = exactContext(h, initial, initial, outer);
  if (failed(first))
    return failure();
  for (BlockArgument arg : h.body->getArguments()) {
    if (arg.getType().isSignlessInteger(64) &&
        (first->binding(arg) || arg.getArgNumber() >= h.ordinaryArguments))
      continue; // Captures were independently admitted above; no fabricated width binding.
    if (!isDigitalCombinationalValueType(arg.getType(), h.module, &*first))
      return reject(h.operation, "collection argument requires a complete finite digital type");
  }
  unsigned comparisonWork = 0;
  if (isReduction(h.kind)) {
    for (unsigned side = 0; side < 2; ++side)
      if (!equivalentAcross(h.inputType.getElementType(), outer,
                            h.body->getArgument(2 * h.slots + side).getType(), &*first, h.module,
                            comparisonWork))
        return reject(h.operation,
                      "paired reducer templates do not preserve initial complete types");
  } else {
    for (unsigned i = 0; i < h.inputs; ++i) {
      auto input = llvm::cast<ShapedType>(h.operation->getOperand(i).getType());
      unsigned argument = h.seeds ? 1 : i;
      if (!equivalentAcross(input.getElementType(), outer, h.body->getArgument(argument).getType(),
                            &*first, h.module, comparisonWork))
        return reject(h.operation, "collection element argument has the wrong complete type");
      auto sameShape = ShapedType::get(h.operation->getContext(), input.getDimensions(),
                                       h.inputType.getElementType());
      if (!equivalentAcross(sameShape, outer, h.inputType, outer, h.module, comparisonWork))
        return failure();
    }
    if (h.seeds &&
        !equivalentAcross(h.operation->getOperand(h.inputs).getType(), outer,
                          h.body->getArgument(0).getType(), &*first, h.module, comparisonWork))
      return failure();
  }
  for (unsigned i = 0; i < h.captures; ++i) {
    auto arg = h.body->getArgument(h.ordinaryArguments + i);
    Value source = h.operation->getOperand(h.inputs + h.seeds + i);
    if (arg.getType().isSignlessInteger(64))
      continue;
    if (!equivalentAcross(source.getType(), outer, arg.getType(), &*first, h.module,
                          comparisonWork))
      return reject(h.operation,
                    "capture argument type does not match the actual captured complete value");
  }
  for (const auto &context : c.contexts) {
    if (isReduction(h.kind)) {
      unsigned slot = 0, work = 0;
      if (!yieldedSlots(h.inputType.getElementType(), h.body->getArgument(2 * h.slots).getType(),
                        h.body->getArgument(2 * h.slots + 1).getType(),
                        h.body->back().getOperand(0).getType(), h, context, outer, slot, work) ||
          slot != h.slots)
        return reject(h.operation,
                      "inner shapes must remain invariant in every reachable reduction context");
      if (work > maximumAnalysisWork - budget.work)
        return reject(h.operation, "collection invariant-shape proof exceeds analysis-work limit");
      budget.work += work;
    }
    for (Operation &op : *h.body)
      if (failed(verifyBodyOperation(&op, context, budget)))
        return failure();
  }
  // Source singletons bypass capture. If handwritten native IR nevertheless
  // retains a template, require its complete first-call template to be valid;
  // this conservative structural admission does not create a runtime combine.
  if (c.contexts.empty())
    for (Operation &op : *h.body)
      if (failed(verifyBodyOperation(&op, *first, budget)))
        return failure();
  if (isReduction(h.kind)) {
    auto expected = typeForSize(c, c.count);
    if (failed(expected))
      return reject(h.operation,
                    "final reduction type or complete footprint overflows the finite domain");
    unsigned slot = 0, work = 0;
    int64_t bits = 0;
    if (!expectedType(h.inputType.getElementType(), h.body->getArgument(2 * h.slots).getType(),
                      h.body->getArgument(2 * h.slots + 1).getType(),
                      h.operation->getResult(0).getType(), expected->widths, h, outer, slot, work,
                      bits) ||
        slot != h.slots)
      return reject(
          h.operation,
          "reduction result differs from the exact all-legal topology-derived complete type");
  } else {
    Type yielded = h.body->back().getOperand(0).getType(),
         actual = h.operation->getResult(0).getType();
    if (h.seeds && !equivalentAcross(yielded, &*first, h.operation->getOperand(h.inputs).getType(),
                                     outer, h.module, comparisonWork))
      return reject(h.operation, "fold and scan must yield the seed's exact complete type");
    if (h.kind != "fold_left") {
      auto shaped = llvm::dyn_cast<ShapedType>(actual);
      if (!shaped)
        return failure();
      auto expected = ShapedType::get(h.operation->getContext(), h.inputType.getDimensions(),
                                      shaped.getElementType());
      if (!equivalentAcross(expected, outer, actual, outer, h.module, comparisonWork))
        return failure();
      actual = shaped.getElementType();
    }
    if (!equivalentAcross(yielded, &*first, actual, outer, h.module, comparisonWork))
      return reject(h.operation,
                    "collection result must preserve exact yielded type and input shape");
  }
  return success();
}
} // namespace

LogicalResult inspectDigitalCollectionRegion(Block *body) {
  auto h = body ? signature(body->getParentOp()) : FailureOr<Signature>(failure());
  return success(succeeded(h) && h->body == body);
}
FailureOr<Value> collectionCaptureSource(BlockArgument argument) {
  auto h = signature(argument.getOwner()->getParentOp());
  if (failed(h) || h->body != argument.getOwner())
    return failure();
  unsigned ordinal = argument.getArgNumber();
  if (ordinal < h->ordinaryArguments)
    return Value();
  Value source = h->operation->getOperand(h->inputs + h->seeds + ordinal - h->ordinaryArguments);
  if (failed(verifyCollectionValueScope(source, h->operation)))
    return failure();
  return source;
}
LogicalResult collectionArgumentDependencies(BlockArgument argument,
                                             SmallVectorImpl<Value> &dependencies) {
  auto h = signature(argument.getOwner()->getParentOp());
  if (failed(h) || h->body != argument.getOwner())
    return failure();
  SmallVector<Value, 4> sources;
  unsigned ordinal = argument.getArgNumber();
  if (ordinal >= h->ordinaryArguments)
    sources.push_back(
        h->operation->getOperand(h->inputs + h->seeds + ordinal - h->ordinaryArguments));
  else if (isReduction(h->kind)) {
    if (ordinal < 2 * h->slots)
      return success(); // static metadata is never a runtime dependency.
    sources.append(h->operation->getOperands().begin(), h->operation->getOperands().end());
  } else if (h->seeds && ordinal == 0) {
    sources.append(h->operation->getOperands().begin(), h->operation->getOperands().end());
  } else
    sources.push_back(h->operation->getOperand(h->seeds ? 0 : ordinal));
  for (Value source : sources)
    if (failed(verifyCollectionValueScope(source, h->operation)))
      return failure();
  dependencies.append(sources.begin(), sources.end());
  return success();
}
FailureOr<Value>
resolveCollectionIntegerTokenValue(Operation *module, StringRef token, StringRef effect,
                                   const ParameterIntegerEnvironment &environment) {
  if (module != environment.module() || (effect != "width" && effect != "shape") ||
      !isCanonicalDigitalWidthToken(token))
    return failure();
  auto header = inspectDigitalRegion(environment.scope());
  if (failed(header) || header->module != module)
    return failure();
  auto value = lookupDigitalRegionToken(environment.scope(), token);
  if (failed(value))
    return failure();
  if (!*value) {
    value = lookupDigitalRegionToken(&module->getRegion(0).front(), token);
    if (failed(value) || !*value)
      return failure();
  }
  if (!(*value).getType().isSignlessInteger(64) ||
      !hasStaticIntegerEffect(*value, module, effect, &environment))
    return failure();
  if (failed(resolveOwnedIntegerExpression(module, *value, environment,
                                           effect == "width"
                                               ? ParameterIntegerMode::MathematicalSigned64
                                               : ParameterIntegerMode::Storage)))
    return failure();
  return value;
}
FailureOr<Expr> resolveCollectionIntegerToken(Operation *module, StringRef token, StringRef effect,
                                              const ParameterIntegerEnvironment &environment) {
  if (module != environment.module() || (effect != "width" && effect != "shape"))
    return failure();
  int64_t literal = 0;
  if (!token.getAsInteger(10, literal))
    return makeLiteralIntegerExpression(literal);
  auto value = resolveCollectionIntegerTokenValue(module, token, effect, environment);
  if (succeeded(value))
    return resolveOwnedIntegerExpression(module, *value, environment,
                                         effect == "width"
                                             ? ParameterIntegerMode::MathematicalSigned64
                                             : ParameterIntegerMode::Storage);
  // Only normal checked module parameter identities may be implicitly referenced.
  auto dimension = resolveStaticShapeDimension(module, token, effect);
  if (failed(dimension) || !dimension->parameter)
    return failure();
  return resolveOwnedIntegerParameter(module, dimension->parameter, environment,
                                      effect == "width" ? ParameterIntegerMode::MathematicalSigned64
                                                        : ParameterIntegerMode::Storage);
}
FailureOr<SmallVector<ParameterIntegerEnvironment, 4>>
collectionOwnIntegerContextsAtUse(Operation *use) {
  if (!use || !named(use->getParentOp(), "nodal.collection"))
    return failure();
  Operation *collection = use->getParentOp();
  SmallVector<ParameterIntegerEnvironment, 4> result;
  if (isDigitalRegionOwner(collection->getParentOp())) {
    auto parents = collectionIntegerContextsAtUse(collection);
    if (failed(parents))
      return failure();
    for (const auto &parent : *parents) {
      auto c = candidate(collection, &parent);
      if (failed(c))
        return failure();
      for (const auto &environment : c->contexts) {
        auto at = environment.atUse(use);
        if (failed(at) || result.size() >= maximumAnalysisWork)
          return failure();
        result.push_back(*at);
      }
    }
  } else {
    auto c = candidate(collection, nullptr);
    if (failed(c))
      return failure();
    for (const auto &environment : c->contexts) {
      auto at = environment.atUse(use);
      if (failed(at))
        return failure();
      result.push_back(*at);
    }
  }
  return result;
}
LogicalResult verifyDigitalCollectionYield(Operation *operation) {
  Operation *parent = operation->getParentOp();
  if (!named(operation, "nodal.collection_yield") || !named(parent, "nodal.collection") ||
      operation->getNumOperands() != 1 || operation->getNumResults() ||
      operation->getNumRegions() || operation->getNumSuccessors() || operation->hasAttr("unit") ||
      !operation->getAttrOfType<DictionaryAttr>("metadata") || operation->getBlock()->empty() ||
      &operation->getBlock()->back() != operation ||
      failed(verifyCollectionValueScope(operation->getOperand(0), operation)))
    return reject(operation, "yield must be the final single visible value of its collection");
  return success();
}
LogicalResult verifyDigitalCollectionOperation(Operation *operation,
                                               const ParameterIntegerEnvironment *enclosing) {
  if (!enclosing && isDigitalRegionOwner(operation->getParentOp())) {
    auto contexts = collectionIntegerContextsAtUse(operation);
    if (failed(contexts))
      return reject(operation, "nested collection cannot derive checked enclosing contexts");
    for (const auto &context : *contexts)
      if (failed(verifyDigitalCollectionOperation(operation, &context)))
        return failure();
    return success();
  }
  auto c = candidate(operation, enclosing);
  if (failed(c))
    return reject(operation, "invalid paired template, captures, unsupported finite width "
                             "transfer, nested reduction or analysis-resource limit");
  VerificationBudget budget;
  return verifyCandidate(*c, budget);
}
LogicalResult verifyDigitalCollectionModel(Operation *module) {
  if (!llvm::isa_and_nonnull<ModuleOp>(module))
    return failure();
  llvm::StringSet<> identities;
  unsigned nodes = 0;
  bool valid = true;
  module->walk([&](Operation *operation) {
    if (owner(operation) != module)
      return;
    if (named(operation, "nodal.collection") &&
        (!identities.insert(text(operation, "region_id")).second || ++nodes > maximumRegionNodes))
      valid = false;
  });
  if (!valid)
    return reject(module, "collection identities must be unique within the finite module budget");
  VerificationBudget budget;
  for (Operation &operation : module->getRegion(0).front())
    if (named(&operation, "nodal.collection")) {
      auto c = candidate(&operation, nullptr);
      if (failed(c))
        return reject(&operation, "invalid raw collection header or finite reduction transfer");
      if (failed(verifyCandidate(*c, budget)))
        return failure();
    }
  return success();
}

namespace {
struct OperationSnapshot {
  Operation *operation;
  DictionaryAttr attributes;
  SmallVector<Value, 4> operands;
  SmallVector<Type, 4> results, arguments;
  SmallVector<Value, 4> argumentValues;
  SmallVector<Block *, 2> blocks;
  Block *parentBlock;
  unsigned regions;
};
FailureOr<SmallVector<OperationSnapshot, 16>> snapshot(Operation *module) {
  SmallVector<OperationSnapshot, 16> result;
  unsigned work = 0;
  bool valid = true;
  module->walk<WalkOrder::PreOrder>([&](Operation *op) {
    if (++work > maximumAnalysisWork) {
      valid = false;
      return WalkResult::interrupt();
    }
    OperationSnapshot entry{
        op, op->getAttrDictionary(), {}, {}, {}, {}, {}, op->getBlock(), op->getNumRegions()};
    entry.operands.append(op->getOperands().begin(), op->getOperands().end());
    entry.results.append(op->getResultTypes().begin(), op->getResultTypes().end());
    for (Region &region : op->getRegions())
      for (Block &block : region) {
        entry.blocks.push_back(&block);
        for (BlockArgument arg : block.getArguments()) {
          entry.arguments.push_back(arg.getType());
          entry.argumentValues.push_back(arg);
        }
      }
    uint64_t charge = entry.operands.size() + entry.results.size() + entry.arguments.size();
    if (charge > maximumAnalysisWork - work) {
      valid = false;
      return WalkResult::interrupt();
    }
    work += charge;
    result.push_back(std::move(entry));
    return WalkResult::advance();
  });
  return valid ? FailureOr<SmallVector<OperationSnapshot, 16>>(std::move(result))
               : FailureOr<SmallVector<OperationSnapshot, 16>>(failure());
}
bool unchanged(Operation *module, ArrayRef<OperationSnapshot> expected) {
  auto actual = snapshot(module);
  if (failed(actual) || actual->size() != expected.size())
    return false;
  for (unsigned i = 0; i < expected.size(); ++i) {
    const auto &a = expected[i], &b = (*actual)[i];
    if (a.operation != b.operation || a.attributes != b.attributes || a.operands != b.operands ||
        a.results != b.results || a.arguments != b.arguments ||
        a.argumentValues != b.argumentValues || a.blocks != b.blocks ||
        a.parentBlock != b.parentBlock || a.regions != b.regions)
      return false;
  }
  return true;
}
} // namespace
struct VerifiedDigitalReduction::Storage {
  Candidate proof;
  SmallVector<OperationSnapshot, 16> snapshot;
};
VerifiedDigitalReduction::VerifiedDigitalReduction(std::shared_ptr<const Storage> storage)
    : storage(std::move(storage)) {}
Operation *VerifiedDigitalReduction::operation() const { return storage->proof.header.operation; }
const Expr &VerifiedDigitalReduction::count() const { return storage->proof.count; }
FailureOr<VerifiedDigitalReduction> analyzeDigitalReduction(Operation *operation) {
  // A nested reduction needs its enclosing ordinary collection proof. Verify the
  // module coordinator first; then there is exactly one (homogeneous) context.
  Operation *module = owner(operation);
  if (!module || !isReduction(text(operation, "kind")) ||
      failed(verifyDigitalCollectionModel(module)))
    return failure();
  FailureOr<Candidate> c = failure();
  if (named(operation->getParentOp(), "nodal.collection") ||
      isBoundedDigitalLoop(operation->getParentOp())) {
    auto parents = collectionIntegerContextsAtUse(operation);
    if (failed(parents) || parents->size() != 1)
      return failure();
    c = candidate(operation, &parents->front());
  } else
    c = candidate(operation, nullptr);
  if (failed(c))
    return failure();
  VerificationBudget budget;
  if (failed(verifyCandidate(*c, budget)))
    return failure();
  auto state = snapshot(module);
  if (failed(state))
    return failure();
  auto storage = std::make_shared<VerifiedDigitalReduction::Storage>();
  storage->proof = std::move(*c);
  storage->snapshot = std::move(*state);
  return VerifiedDigitalReduction(storage);
}
FailureOr<DigitalReductionType> digitalReductionTypeAtPrefix(const VerifiedDigitalReduction &facts,
                                                             int64_t elements) {
  const auto &c = facts.storage->proof;
  if (c.header.kind != "reduce_left" || elements < 1 || elements > c.count.bounds.lower ||
      !unchanged(c.header.module, facts.storage->snapshot))
    return failure();
  return typeForSize(c, makeLiteralIntegerExpression(elements));
}
namespace {
FailureOr<Expr> treeNodeSize(const Candidate &c, unsigned level, int64_t ordinal) {
  if (c.header.kind != "reduce_balanced" || level > 63 || ordinal < 0)
    return failure();
  unsigned minimumRounds = 0;
  uint64_t remainingRounds = static_cast<uint64_t>(c.count.bounds.lower) - 1;
  while (remainingRounds) {
    ++minimumRounds;
    remainingRounds >>= 1;
  }
  if (level > minimumRounds)
    return failure();
  if (level == 63)
    return ordinal == 0 ? FailureOr<Expr>(c.count) : FailureOr<Expr>(failure());
  using Wide = __int128_t;
  int64_t size = static_cast<int64_t>(uint64_t(1) << level);
  Wide offset = Wide(size) * ordinal;
  if (offset >= c.count.bounds.lower)
    return failure();
  if (c.count.bounds.lower - static_cast<int64_t>(offset) >= size)
    return FailureOr<Expr>(makeLiteralIntegerExpression(size));
  auto remaining = composeIntegerExpression(
      "sub", {c.count, makeLiteralIntegerExpression(static_cast<int64_t>(offset))});
  if (failed(remaining))
    return failure();
  // When the node's tail/full boundary crosses the legal count envelope, expose
  // the exact canonical min via a choose_zero test on its bounded quotient.
  // The right tail is present; remaining is positive and the denominator is
  // representable. min(x,B) = choose_zero((x-1)/B, x, B).
  auto previous = composeIntegerExpression("sub", {*remaining, makeLiteralIntegerExpression(1)});
  auto quotient =
      succeeded(previous)
          ? composeIntegerExpression("div", {*previous, makeLiteralIntegerExpression(size)})
          : FailureOr<Expr>(failure());
  auto actual = succeeded(quotient)
                    ? composeIntegerExpression("choose_zero", {*quotient, *remaining,
                                                               makeLiteralIntegerExpression(size)})
                    : FailureOr<Expr>(failure());
  if (failed(actual))
    return failure();
  return *actual;
}
FailureOr<SmallVector<DigitalReductionBodyType, 8>>
bodyTypesForSizes(const Candidate &c, const Expr &leftElements, const Expr &rightElements) {
  const auto &h = c.header;
  SmallVector<Expr, 4> left, right;
  for (const auto &p : c.profiles) {
    auto a = profileWidth(p, leftElements, h.kind == "reduce_balanced"),
         b = profileWidth(p, rightElements, h.kind == "reduce_balanced");
    if (failed(a) || failed(b))
      return failure();
    left.push_back(*a);
    right.push_back(*b);
  }
  auto environment = exactContext(h, left, right, c.enclosing.get());
  if (failed(environment))
    return failure();
  SmallVector<DigitalReductionBodyType, 8> result;
  unsigned work = 0;
  std::function<LogicalResult(Operation &, const ParameterIntegerEnvironment &)> collect =
      [&](Operation &op, const ParameterIntegerEnvironment &scope) -> LogicalResult {
    if (++work > maximumAnalysisWork)
      return failure();
    auto at = scope.atUse(&op);
    if (failed(at))
      return failure();
    for (Value value : op.getResults()) {
      if (value.getType().isSignlessInteger(64) || value.getType().isIndex())
        continue;
      DigitalReductionType type;
      type.skeleton = value.getType();
      auto bits = inferDigitalStorageMaximumBits(value.getType(), &op, &*at);
      if (failed(bits))
        return failure();
      type.maximumBits = *bits;
      std::function<bool(Type, unsigned)> gather = [&](Type t, unsigned depth) {
        if (depth >= digitalTypeMaximumDepth || ++work > maximumAnalysisWork)
          return false;
        if (!kind(t).empty()) {
          auto expression = inferDigitalWidthExpression(t, h.module, &*at);
          if (failed(expression))
            return false;
          type.widths.push_back(*expression);
          return true;
        }
        if (auto shaped = llvm::dyn_cast<ShapedType>(t)) {
          if (!gather(shaped.getElementType(), depth + 1))
            return false;
          SmallVector<StringRef, 4> tokens;
          shaped.getDimensions().split(tokens, ',');
          for (StringRef token : tokens) {
            auto extent = tokenExpression(h.module, token, "shape", &*at);
            if (failed(extent))
              return false;
            type.dimensions.push_back(*extent);
          }
          return true;
        }
        if (auto structure = llvm::dyn_cast<StructType>(t))
          for (Attribute field : structure.getFields())
            if (!gather(llvm::cast<DictionaryAttr>(field).getAs<TypeAttr>("type").getValue(),
                        depth + 1))
              return false;
        return true;
      };
      if (!gather(value.getType(), 0))
        return failure();
      result.push_back({value, std::move(type)});
    }
    if (named(&op, "nodal.collection")) {
      auto child = candidate(&op, &*at);
      if (failed(child) || child->contexts.size() != 1)
        return failure();
      for (Operation &nested : *child->header.body)
        if (failed(collect(nested, child->contexts.front())))
          return failure();
    }
    return success();
  };
  for (Operation &op : *h.body)
    if (failed(collect(op, *environment)))
      return failure();
  return result;
}
} // namespace
FailureOr<DigitalReductionType> digitalReductionFinalType(const VerifiedDigitalReduction &facts) {
  const auto &c = facts.storage->proof;
  if (!unchanged(c.header.module, facts.storage->snapshot))
    return failure();
  return typeForSize(c, c.count);
}
FailureOr<DigitalReductionType>
digitalReductionTypeAtTreeNode(const VerifiedDigitalReduction &facts, unsigned level,
                               int64_t ordinal) {
  const auto &c = facts.storage->proof;
  if (!unchanged(c.header.module, facts.storage->snapshot))
    return failure();
  auto size = treeNodeSize(c, level, ordinal);
  return succeeded(size) ? typeForSize(c, *size) : FailureOr<DigitalReductionType>(failure());
}
FailureOr<SmallVector<DigitalReductionBodyType, 8>>
instantiateDigitalReductionBodyTypes(const VerifiedDigitalReduction &facts, int64_t leftElements,
                                     int64_t rightElements) {
  const auto &c = facts.storage->proof;
  using Wide = __int128_t;
  if (leftElements < 1 || rightElements < 1 ||
      Wide(leftElements) + rightElements > c.count.bounds.lower ||
      !unchanged(c.header.module, facts.storage->snapshot))
    return failure();
  if (c.header.kind == "reduce_left") {
    if (rightElements != 1)
      return failure();
  } else {
    if (rightElements > leftElements || (uint64_t(leftElements) & (uint64_t(leftElements) - 1)))
      return failure();
    // A full pair occurs in every legal population admitted above. A partial
    // pair of these fixed sizes must be the actual final pair of a fixed count;
    // smaller plausible sizes alone do not establish an existing tree node.
    if (rightElements < leftElements && (c.count.bounds.lower != c.count.bounds.upper ||
                                         Wide(c.count.bounds.lower) % (Wide(leftElements) * 2) !=
                                             Wide(leftElements) + rightElements))
      return failure();
  }
  return bodyTypesForSizes(c, makeLiteralIntegerExpression(leftElements),
                           makeLiteralIntegerExpression(rightElements));
}
FailureOr<SmallVector<DigitalReductionBodyType, 8>>
instantiateDigitalReductionBodyTypesAtTreeNode(const VerifiedDigitalReduction &facts,
                                               unsigned level, int64_t ordinal) {
  const auto &c = facts.storage->proof;
  if (level == 0 || level > 63 || ordinal < 0 ||
      !unchanged(c.header.module, facts.storage->snapshot))
    return failure();
  using Wide = __int128_t;
  Wide leftOrdinal = Wide(ordinal) * 2;
  if (leftOrdinal + 1 > std::numeric_limits<int64_t>::max())
    return failure();
  auto parent = treeNodeSize(c, level, ordinal);
  auto left = treeNodeSize(c, level - 1, static_cast<int64_t>(leftOrdinal));
  auto right = treeNodeSize(c, level - 1, static_cast<int64_t>(leftOrdinal + 1));
  if (failed(parent) || failed(left) || failed(right))
    return failure(); // A carry has no callback or body instantiation.
  return bodyTypesForSizes(c, *left, *right);
}

LogicalResult verifyDigitalCollectionOperation(Operation *operation) {
  return verifyDigitalCollectionOperation(operation, nullptr);
}
} // namespace nodal
