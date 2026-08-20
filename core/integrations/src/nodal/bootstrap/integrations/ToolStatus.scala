package nodal.bootstrap.integrations

enum ToolStatus:
  case Available(version: String)
  case Unavailable(reason: String)
