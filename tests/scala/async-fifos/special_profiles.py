"""Legal target stimuli for FIFO wiring and reset-policy variants.

The enclosing bench records accepted transfers. Its source tick must also record
reads for the shared-clock profile, its combined tick must clock all three chain
domains, and its scoreboard must use the effective constant valid/ready of the
continuous/unattached profiles rather than their unused external ports.
"""
from __future__ import annotations


def stimulus(profile: str, high: bool) -> str:
    """Return a bounded SystemVerilog initial-block body for an actual FIFO."""
    asserted, released = (1, 0) if high else (0, 1)
    if profile == "fifo_sync":
        return f'''
  begin : shared_clock_profile
    integer target, attempts;
    inputValid=0; outputReady=0; reset={asserted}; #1;
    if(inputReady !== 0 || outputValid !== 0) $fatal(1,"shared-clock reset mask");
    src_tick; reset={released}; repeat(3) src_tick;
    if(inputReady !== 1 || outputValid !== 0) $fatal(1,"shared-clock reset recovery");
    for(round=0; round<3; round=round+1) begin
      target=writeCount+DEPTH+1; outputReady=0;
      for(i=0; i<DEPTH; i=i+1) begin
        inputValid=1; inputPayload=nextPayload; nextPayload=nextPayload+1; #1;
        if(inputReady !== 1) $fatal(1,"shared-clock FIFO capacity too small");
        src_tick;
      end
      if(inputReady !== 0) $fatal(1,"shared-clock FIFO capacity too large");
      // This pending offer stays unchanged until an actual shared-edge acceptance.
      inputPayload=nextPayload; nextPayload=nextPayload+1;
      repeat(4) begin
        src_tick;
        if(writeCount != target-1) $fatal(1,"shared-clock full FIFO accepted an offer");
      end
      if(outputValid !== 1) $fatal(1,"shared-clock full FIFO never became visible");
      repeat(3) begin
        src_tick;
        if(outputPayload !== expected[readCount]) $fatal(1,"shared-clock stalled output changed");
      end
      for(attempts=0; attempts<8*DEPTH+32 && readCount<target; attempts=attempts+1) begin
        outputReady=(attempts%4 != 0);
        src_tick;
        if(writeCount == target) inputValid=0;
        if(writeCount > target) $fatal(1,"shared-clock pending offer accepted twice");
      end
      if(writeCount != target || readCount != target) $fatal(1,"shared-clock drain timed out");
      outputReady=1; repeat(4) src_tick;
      if(outputValid !== 0 || inputReady !== 1 || readCount != writeCount)
        $fatal(1,"shared-clock empty state after wrap/drain");
    end
    // Flush queued data, with both synchronous domains observing the same edge.
    outputReady=0; inputValid=1; inputPayload=31;
    if(inputReady !== 1) $fatal(1,"shared-clock flush setup has no space");
    src_tick; inputPayload=47;
    if(inputReady !== 1) $fatal(1,"shared-clock flush setup lost space");
    src_tick; inputValid=0;
    reset={asserted}; #1; readCount=writeCount;
    if(inputReady !== 0 || outputValid !== 0) $fatal(1,"shared-clock flush mask");
    src_tick; reset={released}; repeat(3) src_tick;
    outputReady=1; repeat(4) src_tick;
    if(outputValid !== 0 || readCount != writeCount) $fatal(1,"shared-clock stale data after reset");
    inputValid=1; inputPayload=99;
    if(inputReady !== 1) $fatal(1,"shared-clock post-reset source not ready");
    src_tick; inputValid=0; repeat(8) src_tick;
    if(readCount != writeCount || outputValid !== 0) $fatal(1,"shared-clock post-reset delivery");
  end
'''
    if profile == "fifo_unattached":
        return f'''
  begin : unattached_profile
    integer target, readsBefore;
    inputValid=0; outputReady=0; reset={asserted}; #1;
    if(inputReady !== 0 || outputValid !== 0) $fatal(1,"unattached reset mask");
    both_tick; reset={released}; repeat(3) both_tick;
    if(inputReady !== 1 || outputValid !== 0) $fatal(1,"unattached reset recovery");
    for(round=0; round<2; round=round+1) begin
      target=writeCount+DEPTH; readsBefore=readCount;
      for(i=0; i<DEPTH; i=i+1) begin
        inputValid=1; inputPayload=nextPayload; nextPayload=nextPayload+1; #1;
        if(inputReady !== 1) $fatal(1,"unattached FIFO capacity too small");
        src_tick;
      end
      if(inputReady !== 0) $fatal(1,"unattached FIFO capacity too large");
      inputPayload=nextPayload;
      repeat(4) both_tick;
      if(outputValid !== 1) $fatal(1,"unattached FIFO head not visible");
      // The external ready port is deliberately not connected to accept().
      for(i=0; i<12; i=i+1) begin
        outputReady=(i%3 != 0); both_tick;
        if(writeCount != target || readCount != readsBefore)
          $fatal(1,"unattached sink transferred or released source space");
        if(inputReady !== 0 || outputValid !== 1 || outputPayload !== expected[readCount])
          $fatal(1,"unattached full FIFO did not hold its head");
      end
      if(round == 0) begin
        // Reset is the only way an unattached sink can release queued entries.
        reset={asserted}; inputValid=0; #1; readCount=writeCount;
        if(inputReady !== 0 || outputValid !== 0) $fatal(1,"unattached flush mask");
        both_tick; reset={released}; repeat(4) both_tick;
        if(inputReady !== 1 || outputValid !== 0) $fatal(1,"unattached flush recovery");
      end
    end
    if(writeCount != 2*DEPTH || readCount != DEPTH)
      $fatal(1,"unattached accepted-count mismatch");
  end
'''
    if profile == "fifo_continuous":
        return f'''
  begin : continuous_profile
    integer target, attempts;
    // Stream(payload) has effective valid=1, even though inputValid stays zero.
    // Keep this payload constant before, during and after every reset or stall.
    inputPayload=7; inputValid=0; outputReady=0; reset={asserted}; #1;
    if(inputReady !== 0 || outputValid !== 0) $fatal(1,"continuous reset mask");
    both_tick; reset={released};
    for(round=0; round<3; round=round+1) begin
      target=writeCount+DEPTH; outputReady=0;
      for(attempts=0; attempts<DEPTH+8 && writeCount<target; attempts=attempts+1) src_tick;
      if(writeCount != target || inputReady !== 0) $fatal(1,"continuous exact capacity");
      repeat(3) src_tick;
      if(writeCount != target) $fatal(1,"continuous full FIFO accepted an extra item");
      repeat(4) dst_tick;
      if(outputValid !== 1) $fatal(1,"continuous accepted data not visible");
      repeat(3) begin
        dst_tick;
        if(outputPayload !== expected[readCount]) $fatal(1,"continuous stalled output changed");
      end
      // Freeze the always-valid producer while consuming this exact batch.
      outputReady=1;
      for(i=0; i<DEPTH; i=i+1) begin
        if(outputValid !== 1) $fatal(1,"continuous batch emptied too early");
        dst_tick;
      end
      if(readCount != target || outputValid !== 0) $fatal(1,"continuous batch drain mismatch");
      if(inputReady !== 0) $fatal(1,"continuous return pointer bypassed synchronizers");
      src_tick;
      if(inputReady !== 0) $fatal(1,"continuous return pointer arrived too early");
      src_tick;
      if(inputReady !== 1 || writeCount != target) $fatal(1,"continuous returned-space visibility");
    end
    // Queue two entries, flush them, then prove the destination stays empty
    // until the continuously valid source receives fresh post-reset edges.
    outputReady=0; target=writeCount+2; src_tick; src_tick;
    if(writeCount != target) $fatal(1,"continuous flush setup acceptance");
    reset={asserted}; #1; readCount=writeCount;
    if(inputReady !== 0 || outputValid !== 0) $fatal(1,"continuous flush mask");
    both_tick; reset={released}; outputReady=1; repeat(4) dst_tick;
    if(outputValid !== 0 || readCount != writeCount) $fatal(1,"continuous pre-reset data survived");
    outputReady=0; target=writeCount+DEPTH;
    for(attempts=0; attempts<DEPTH+8 && writeCount<target; attempts=attempts+1) src_tick;
    if(writeCount != target || inputReady !== 0) $fatal(1,"continuous post-reset refill");
    repeat(4) dst_tick; outputReady=1;
    for(i=0; i<DEPTH; i=i+1) begin
      if(outputValid !== 1) $fatal(1,"continuous post-reset batch emptied early");
      dst_tick;
    end
    if(readCount != writeCount || outputValid !== 0) $fatal(1,"continuous post-reset delivery");
  end
'''
    if profile == "fifo_chain":
        return f'''
  begin : chained_profile
    integer target, attempts, previousWrites;
    if(DEPTH != 4) $fatal(1,"chain witness requires two depth-four FIFOs");
    inputValid=0; outputReady=0; reset={asserted}; #1;
    if(inputReady !== 0 || outputValid !== 0) $fatal(1,"chain reset mask");
    both_tick; reset={released}; repeat(3) both_tick;
    if(inputReady !== 1 || outputValid !== 0) $fatal(1,"chain reset recovery");
    target=2*DEPTH; inputValid=1; inputPayload=nextPayload;
    for(attempts=0; attempts<32*DEPTH && writeCount<target; attempts=attempts+1) begin
      previousWrites=writeCount; both_tick;
      if(writeCount != previousWrites) begin
        nextPayload=nextPayload+1; inputPayload=nextPayload;
      end
    end
    if(writeCount != target) $fatal(1,"chain failed to accept its combined capacity");
    // Keep the ninth offer pending while every domain continues clocking.
    repeat(16) begin
      both_tick;
      if(writeCount != target || readCount != 0) $fatal(1,"chain exceeded total capacity eight");
    end
    if(inputReady !== 0 || outputValid !== 1) $fatal(1,"chain full-state visibility");
    if(outputPayload !== expected[readCount]) $fatal(1,"chain stalled head changed");
    // Continue the pending offer, wrap both FIFOs repeatedly, and drain under
    // deterministic backpressure. Only an accepted edge advances the producer.
    target=10*DEPTH;
    for(attempts=0; attempts<32*target && readCount<target; attempts=attempts+1) begin
      outputReady=(attempts%7 > 1); previousWrites=writeCount; both_tick;
      if(writeCount != previousWrites) begin
        nextPayload=nextPayload+1;
        if(writeCount == target) inputValid=0;
        else inputPayload=nextPayload;
      end
      if(writeCount > target) $fatal(1,"chain producer accepted an extra item");
    end
    if(writeCount != target || readCount != target) $fatal(1,"chain wrap/drain timed out");
    outputReady=1; repeat(12) both_tick;
    if(inputReady !== 1 || outputValid !== 0 || readCount != writeCount)
      $fatal(1,"chain did not return to empty");
    // Populate both stages before a common reset, then check fresh delivery.
    outputReady=0; target=writeCount+DEPTH+2; inputValid=1; inputPayload=nextPayload;
    for(attempts=0; attempts<32*DEPTH && writeCount<target; attempts=attempts+1) begin
      previousWrites=writeCount; both_tick;
      if(writeCount != previousWrites) begin
        nextPayload=nextPayload+1;
        if(writeCount == target) inputValid=0;
        else inputPayload=nextPayload;
      end
    end
    if(writeCount != target) $fatal(1,"chain flush setup timed out");
    repeat(8) both_tick;
    if(outputValid !== 1) $fatal(1,"chain flush setup did not reach destination");
    reset={asserted}; #1; readCount=writeCount;
    if(inputReady !== 0 || outputValid !== 0) $fatal(1,"chain flush mask");
    both_tick; reset={released}; outputReady=1; repeat(12) both_tick;
    if(outputValid !== 0 || inputReady !== 1 || readCount != writeCount)
      $fatal(1,"chain pre-reset data survived");
    target=writeCount+1; inputValid=1; inputPayload=nextPayload;
    for(attempts=0; attempts<32 && readCount<target; attempts=attempts+1) begin
      both_tick;
      if(writeCount == target) inputValid=0;
      if(writeCount > target) $fatal(1,"chain duplicated post-reset offer");
    end
    if(readCount != target || writeCount != target || outputValid !== 0)
      $fatal(1,"chain post-reset delivery timed out");
  end
'''
    raise ValueError(f"unknown special FIFO profile: {profile}")
