
package zno.core.front.fetch

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._
import zno.core.front._

class FetchUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val ibus     = new ZnoInstBusIO
    val tgt_in   = Flipped(Decoupled(p.FetchBlockAddress()))
    val blk_out  = Decoupled(new FetchBlock)
  })

  io.tgt_in.ready   := (io.ibus.req.ready && io.blk_out.ready)
  io.ibus.req.valid := io.tgt_in.valid
  io.ibus.req.bits  := io.tgt_in.bits

  io.blk_out <> io.ibus.resp
}

