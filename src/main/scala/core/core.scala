
package zno.core

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.core.uarch._
import zno.common._


// The front-end of the machine.
class ZnoFrontcore(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    // Connection from the fetch unit to some external memory
    val ibus = new ZnoFetchIO
    // Input branch target to the fetch unit
    val brn_tgt = Flipped(Valid(UInt(p.xlen.W)))
    // Output fetch block to the FBQ
    val fbq_enq = Decoupled(new FbqEntry)
  })

  val ifu = Module(new zno.core.front.fetch.FetchUnit)

  ifu.io.ibus <> io.ibus
  ifu.io.brn_tgt := io.brn_tgt
  ifu.io.fbq_enq <> io.fbq_enq

}

// Bridge between the front-end and back-end of the machine. 
class ZnoMidcore(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    // Input fetch block from the FBQ
    val fbq_deq = Flipped(Decoupled(new FbqEntry))
  })

  io.fbq_deq.ready := true.B // FIXME

}

// The back-end of the machine. 
class ZnoBackcore(implicit p: ZnoParam) extends Module {
}

class ZnoCore extends Module {
  implicit val p = ZnoParam()

  val io = IO(new Bundle {
    // Connection from the frontend to some external memory
    val ibus  = new ZnoFetchIO
  })

  // This seems like a reasonable way of slicing things up. 
  val fc = Module(new ZnoFrontcore)
  val mc = Module(new ZnoMidcore)
  val bc = Module(new ZnoBackcore)

  // The "fetch block queue" decoupling [ZnoFrontcore] from [ZnoMidcore].
  val fbq = Module(new Queue(new FbqEntry, p.fbq_sz))


  fc.io.ibus    <> io.ibus
  fc.io.brn_tgt := DontCare // FIXME

  fc.io.fbq_enq <> fbq.io.enq
  mc.io.fbq_deq <> fbq.io.deq

}


