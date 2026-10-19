# Owned memory callback checks

Run `run.py` with existing pinned Scala 3.8.4 compiler classpath, API classpath,
constructor plugin jar, Java executable and an output parent under `.work`.
The focused harness checks direct and separately compiled sequential writes,
source-located branch/guard read rejection, rejection of writes in pure,
structural and loop callbacks, and rejection of direct private kernel calls.
It does not download tools, invoke CI or run repository-wide qualification.

Public source-to-native witnesses live in
`core/scala/testkit/test/src/nodal_external/Increment56MemoryBridgeSource.scala`.
Runtime ownership and transaction cases live in `Increment56MemoryCaptureTests`;
independent bridge snapshot validation lives in `Increment56MemoryBridgeTests`.
