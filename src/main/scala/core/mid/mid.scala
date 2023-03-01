package zno.core.mid

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._
import zno.core.front.decode._

// Bridge between the front-end and back-end of the machine. 
class ZnoMidcore(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val opq = Flipped(Decoupled(new DecodeBlock))
  })

  io.opq.ready := true.B

}


