package nodal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compiler-owned bounded constructor-effect proof, independent of allocation capture metadata.
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
