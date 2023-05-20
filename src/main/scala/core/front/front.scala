
package zno.core.front

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._

import zno.core.front.decode._


class FetchUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val ibus = new ZnoInstBusIO
    val ftgt = Flipped(Decoupled(new FetchBlockAddr))
    val fblk = Decoupled(new FetchBlock)
  })

  io.ibus.req.valid := io.ftgt.valid
  io.ibus.req.bits  := io.ftgt.bits
  io.ftgt.ready     := io.ibus.resp.fire

  io.fblk <> io.ibus.resp
}

class PredecodeUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val fblk = Flipped(Decoupled(new FetchBlock))
    val pdblk = Decoupled(new PredecodeBlock)
  })
  val pdec = Seq.fill(p.dec_win.size)(Module(new Predecoder))
  for (idx <- 0 until p.dec_win.size) {
    pdec(idx).io.opcd := io.fblk.bits.data(idx)
    io.pdblk.bits.data(idx) := pdec(idx).io.out
  }
  io.pdblk.bits.addr := io.fblk.bits.addr
  io.pdblk.valid := io.fblk.valid
  io.fblk.ready := io.pdblk.ready

}

class DecodeUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val fblk = Flipped(Decoupled(new FetchBlock))
    val dblk = Decoupled(new DecodeBlock)
  })
  val dec = Seq.fill(p.dec_win.size)(Module(new UopDecoder))

  for (idx <- 0 until p.dec_win.size) {
    dec(idx).inst          := io.fblk.bits.data(idx)
    io.dblk.bits.data(idx) := dec(idx).out
  }
  io.dblk.bits.addr := io.fblk.bits.addr
  io.dblk.valid     := io.fblk.valid
  io.fblk.ready     := io.dblk.ready
}

// The front-end of the machine.
class ZnoFrontcore(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    // Connection to instruction memory
    val ibus = new ZnoInstBusIO
    // Connection to a queue of decoded blocks
    val dblk = Decoupled(new DecodeBlock)

    // Architectural control-flow event
    val arch_cfe = Flipped(Decoupled(new ArchitecturalCfEvent))
  })

  val bpu = Module(new BranchPredictionUnit)
  val cfm = Module(new ControlFlowMap)
  val ftq = Module(new Queue(new FetchBlockAddr, p.ftq.size))
  val ifu = Module(new FetchUnit)
  val fbq = Module(new Queue(new FetchBlock, p.fbq.size))
  val pdu = Module(new PredecodeUnit)
  val idu = Module(new DecodeUnit)

  cfm.io.arch_cfe <> io.arch_cfe
  cfm.io.spec_cfe <> bpu.io.spec_cfe
  cfm.io.ftgt <> ftq.io.enq
  cfm.io.pdblk <> pdu.io.pdblk

  ifu.io.ibus <> io.ibus
  ifu.io.ftgt <> ftq.io.deq
  ifu.io.fblk <> fbq.io.enq

  pdu.io.fblk <> fbq.io.deq

  idu.io.fblk <> fbq.io.deq
  idu.io.dblk <> io.dblk


}


