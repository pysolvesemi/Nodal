package nodal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compiler-owned bounded callback-effect proof, independent of allocation capture metadata.
 * Profiles 1 and 3 retain the legacy structural-allocation contract (3 also permits writes).
 * Profile 4 attests read-only helpers/singletons; profile 8 permits the bounded structural
 * conditional and continuous-write profile for helpers/singletons, without allocation.
 * Profile 16 permits behavioral guard/register-update helpers, with pure predicates.
 * The frontend validates the kind, exact signature, profile and transitive dependency records.
 * User source must not declare this annotation. It is a compiler ABI, not a security boundary.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ConstructorEffectSummary {
  int version();

  String kind();

  String signature();

  int profile();

  String dependencies();
}
