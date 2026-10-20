package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.{HierarchyPayloadParent, Increment58GeneratedAreaScaleSource}
import utest.*

object Increment58BoundaryArtifactTests extends TestSuite:
  private def canonical(snapshot: ConstructionSnapshot): String =
    ReproducibilityContract.canonicalSnapshot(snapshot)

  val tests = Tests:
    test("complete Interface artifact retains formal contracts and exact actual boundary facts"):
      val snapshot = ConstructionKernel.inspect(new HierarchyPayloadParent("no-access"))
      val original = canonical(snapshot)
      val endpoint = snapshot.interfaceContracts.find(_.boundary.nonEmpty).get
      val boundary = endpoint.boundary.get
      val role = endpoint.roles.find(_.name != endpoint.role).get
      val member = role.members.head
      val changedMembers = Vector(
        member.copy(path = member.path + "_other"),
        member.copy(name = member.name + "_other"),
        member.copy(role = "changed"),
        member.copy(access = "changed"),
        member.copy(dataType = "Bits(7)"),
        member.copy(protocol = "changed"),
        member.copy(mode = Some("read_only")),
        member.copy(nestedRoles = Vector("nested" -> "monitor")),
        member.copy(nestedInterfaces = Vector("nested" -> "other.Identity")),
        member.copy(analogAttributes = Vector("discipline" -> "changed"))
      )
      val variants = Vector(
        endpoint.copy(boundary = Some(boundary.copy(instance = boundary.instance + "_other"))),
        endpoint.copy(boundary = Some(boundary.copy(endpoint = boundary.endpoint + "_other"))),
        endpoint.copy(actualMemberTypes = endpoint.actualMemberTypes.reverse),
        endpoint.copy(actualMemberTypes =
          endpoint.actualMemberTypes.map((name, _) =>
            name -> "Bits(1)"
          )
        ),
        endpoint.copy(members = endpoint.members.reverse)
      ) ++ changedMembers.map(changed =>
        endpoint.copy(roles =
          endpoint.roles.map(value =>
            if value.name == role.name then role.copy(members = role.members.updated(0, changed))
            else value
          )
        )
      )
      assert(
        original.contains("\"interface_contracts\""),
        original.contains("\"interface_boundary\""),
        original.contains("\"actual_member_types\""),
        original.contains(boundary.instance),
        original.contains(boundary.endpoint)
      )
      variants.foreach: changed =>
        assert(canonical(snapshot.copy(interfaceContracts =
          snapshot.interfaceContracts.map(value =>
            if value.path == endpoint.path then changed else value
          )
        )) != original)
      val declarationChanged = snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(declarations =
            module.declarations.map(declaration =>
              if declaration.path != endpoint.path then declaration
              else
                declaration.copy(interfaceBoundary =
                  Some(boundary.copy(endpoint = boundary.endpoint + "_changed"))
                )
            )
          )
        )
      )
      assert(canonical(declarationChanged) != original)

    test("Interface artifact ordering is stable and connectivity references remain explicit"):
      val snapshot = ConstructionKernel.inspect(new HierarchyPayloadParent("late"))
      val original = canonical(snapshot)
      val permuted = snapshot.copy(
        interfaceContracts = snapshot.interfaceContracts.reverse.map(endpoint =>
          endpoint.copy(roles = endpoint.roles.reverse)
        ),
        digitalConnectivity = snapshot.digitalConnectivity.reverse
      )
      assert(canonical(permuted) == original)
      assert(original.contains("\"digital_connectivity\""))
      val effect = snapshot.digitalConnectivity.head
      val changed = snapshot.copy(digitalConnectivity =
        snapshot.digitalConnectivity.updated(0, effect.copy(target = effect.target + "_other"))
      )
      assert(canonical(changed) != original)
      assert(canonical(ConstructionKernel.inspect(new HierarchyPayloadParent("late"))) == original)

    test("generated Area artifact preserves family exports projections and exact scope"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedAreaScaleSource)
      val original = canonical(snapshot)
      val family = snapshot.generatedRegions.head
      val expression = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      val member = expression.generatedMember.get
      val area = snapshot.areas.find(_.generatedScope.nonEmpty).get
      val scope = area.generatedScope.get
      assert(
        original.contains("\"areas\""),
        original.contains("\"yielded_area\""),
        original.contains("\"exports\""),
        original.contains("\"generated_member\""),
        original.contains("\"read_only\""),
        original.contains("\"nested.other\"")
      )
      val changedFamily = family.copy(
        yieldedArea = family.yieldedArea.map(_ + "_other"),
        exports = family.exports.map(value => value.copy(access = "read_only"))
      )
      val changedExpression = expression.copy(generatedMember =
        Some(member.copy(ordinal = "1", access = "read_only"))
      )
      val changedArea =
        area.copy(generatedScope = Some(scope.copy(induction = scope.induction + "_other")))
      val mutations = Vector(
        snapshot.copy(generatedRegions = snapshot.generatedRegions.updated(0, changedFamily)),
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(value =>
            if value.path == expression.path then changedExpression else value
          )
        ),
        snapshot.copy(areas =
          snapshot.areas.map(value =>
            if value.path == area.path then changedArea else value
          )
        )
      )
      mutations.foreach(changed => assert(canonical(changed) != original))

    test("retained generated family metadata cannot disappear without a digital body"):
      val snapshot = ConstructionKernel.inspect(new Increment58GeneratedAreaScaleSource)
      val retained = snapshot.copy(generatedRegions =
        snapshot.generatedRegions.map(_.copy(digitalBody = None))
      )
      val artifact = canonical(retained)
      assert(
        artifact.contains("\"digital_generates\""),
        artifact.contains("\"yielded_area\""),
        artifact.contains("\"exports\""),
        artifact.contains("\"nested.other\"")
      )
      assert(canonical(retained.copy(generatedRegions =
        retained.generatedRegions.map(_.copy(yieldedArea = None, exports = Vector.empty))
      )) != artifact)
