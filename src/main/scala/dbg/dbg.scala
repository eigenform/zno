package zno.dbg

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

// NOTE: You can apparently use [[BoringUtils]] to create connections
// without respect for the hierarchy. 

object MonitorProbe {
  def apply(key: String, value: UInt) = {
    val mon_en = WireInit(false.B)
    val probe_name = "mon_prb_" ++ key
    BoringUtils.addSink(mon_en, probe_name)
    when (mon_en) {
      printf(p"[monitor] $probe_name: $value%x\n")
    }
  }
}
