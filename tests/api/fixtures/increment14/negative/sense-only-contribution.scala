package examples.interfacepipeline

import nodal.*

val senseOnlyTerminal = terminal(Electrical, "senseOnly")

// diagnostic-anchor: NODAL-AMS-014
senseOnlyTerminal.senseView.contribute(1.0.real)
