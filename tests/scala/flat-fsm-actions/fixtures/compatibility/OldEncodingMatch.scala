import nodal.*
object OldEncodingMatch:
  def name(value: FsmEncoding): String = value match
    case FsmEncoding.Compact => "compact"
    case FsmEncoding.OneHot => "one-hot"
    case FsmEncoding.Gray => "gray"
    case FsmEncoding.Auto => "auto"
