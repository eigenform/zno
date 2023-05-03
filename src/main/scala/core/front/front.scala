
package zno.core.front

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._

import zno.core.front.predecode._
import zno.core.front.decode._


class FetchUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val ibus = new ZnoInstBusIO
    val ftgt = Flipped(Decoupled(p.FetchBlockAddr()))
    val fblk = Decoupled(new FetchBlock)
  })

  val ftq = Module(new Queue(p.FetchBlockAddr(), p.ftq_sz))
  ftq.io.enq <> io.ftgt

  io.ibus.req.valid := ftq.io.deq.valid
  io.ibus.req.bits  := ftq.io.deq.bits
  ftq.io.deq.ready  := io.ibus.resp.fire

  io.fblk <> io.ibus.resp
}

class DecodeUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val fblk = Flipped(Decoupled(new FetchBlock))
    val dblk = Decoupled(new DecodeBlock)
  })

  val dec = Seq.fill(p.dec_bw)(Module(new UopDecoder))

  val fbq = Module(new Queue(new FetchBlock, p.fbq_sz))
  fbq.io.enq <> io.fblk

  for (idx <- 0 until p.dec_bw) {
    dec(idx).inst          := fbq.io.deq.bits.data(idx)
    io.dblk.bits.data(idx) := dec(idx).out
  }
  io.dblk.bits.addr := fbq.io.deq.bits.addr
  io.dblk.valid    := fbq.io.deq.valid
  fbq.io.deq.ready := io.dblk.ready

}


// The front-end of the machine.
class ZnoFrontcore(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val ibus = new ZnoInstBusIO
    val npc  = Flipped(Decoupled(p.ProgramCounter()))
    val dblk = Decoupled(new DecodeBlock)
  })

  val ifu = Module(new FetchUnit)
  ifu.io.ibus <> io.ibus
  ifu.io.ftgt.bits  := p.FetchBlockAddr.from_pc(io.npc.bits)
  ifu.io.ftgt.valid := io.npc.valid
  io.npc.ready      := ifu.io.ftgt.ready

  val idu = Module(new DecodeUnit)
  idu.io.fblk <> ifu.io.fblk
  idu.io.dblk <> io.dblk


}


