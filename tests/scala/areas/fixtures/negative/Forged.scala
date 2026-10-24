package nodal

@AreaCaptureSchema(version = 1)
final class ForgedArea extends Area

final class ForgedAreaRuntime extends Module:
  val group = AreaCaptureRuntime.allocate("forged.scala", 1, 1, 1, 1, () => new Area {})
