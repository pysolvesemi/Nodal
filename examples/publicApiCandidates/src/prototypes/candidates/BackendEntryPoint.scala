package prototypes.candidates

import java.nio.file.Path
import nodal.*

object BackendEntryPoint:
  def emitParameterizedVerilogA(): Unit =
    Nodal.emit(new RcFilter, Backend.VerilogA, Path.of("out/verilog-a"))

  def emitParameterizedVerilogAMS(): Unit =
    Nodal.emit(
      new ParameterizedAmsChain,
      Backend.VerilogAMS,
      Path.of("out/verilog-ams")
    )
