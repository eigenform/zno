
package zno.core.front

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._



class FetchUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val ibus = new ZnoInstBusIO
    val ftgt = Flipped(Decoupled(p.FetchBlockAddr()))
    val fblk = Decoupled(new FetchBlock)
  })

  io.ibus.req.valid := io.ftgt.valid
  io.ibus.req.bits  := io.ftgt.bits
  io.ftgt.ready     := io.ibus.resp.fire

  io.fblk <> io.ibus.resp
}


// Predecode unit. 
//
// NOTE: The original fetch block *flows through* this logic. 
class PredecodeUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val fblk = Flipped(Decoupled(new FetchBlock))
    val pdblk = Decoupled(new PredecodeBlock)
  })
  val pdec = Seq.fill(p.dec_win.size)(Module(new Predecoder))

  for (idx <- 0 until p.dec_win.size) {
    pdec(idx).io.opcd := io.fblk.bits.data(idx)
    io.pdblk.bits.pdmops(idx) := pdec(idx).io.out
  }
  io.pdblk.bits.fblk := io.fblk.bits
  io.pdblk.valid     := io.fblk.valid
  io.fblk.ready      := io.pdblk.ready
}


// The front-end of the machine.
class ZnoFrontcore(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle { 
    // Connection to instruction memory
    val ibus = new ZnoInstBusIO
    // Architectural control-flow event
    val arch_cfe = Flipped(Decoupled(new ControlFlowEvent))
    // To midcore/instruction decode
    val cfmblk = Decoupled(new CfmBlock)
  })

  val cfm = Module(new ControlFlowMap)

  val ifu = Module(new FetchUnit)
  val fbq = Module(new Queue(new FetchBlock, p.fbq.size))
  val pdu = Module(new PredecodeUnit)

  val ftq = Module(new Queue(p.FetchBlockAddr(), p.ftq.size))
  ftq.io.enq.valid := false.B
  ftq.io.enq.bits  := 0.U.asTypeOf(p.FetchBlockAddr())

  // Instruction fetch is connected to instruction memory
  ifu.io.ibus <> io.ibus

  // Architectural events flow into the CFM
  io.arch_cfe <> cfm.io.arch_cfe

  // CFM sends fetch targets to the FTQ
  ftq.io.enq <> cfm.io.ftq

  // The CFM maintains a queue for fetch targets
  cfm.io.ftq  <> ifu.io.ftgt    // FTQ => IFU

  // A queue of fetched blocks separates fetch from predecode
  ifu.io.fblk <> fbq.io.enq     // IFU => FBQ
  fbq.io.deq  <> pdu.io.fblk    // FBQ => PDU

  // Predecoded blocks flow into the CFM
  pdu.io.pdblk <> cfm.io.pdblk  // PDU => CFM

  // The CFM sends fetched blocks down the pipeline to the midcore
  cfm.io.cfmblk <> io.cfmblk

}


