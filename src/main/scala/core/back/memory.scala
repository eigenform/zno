package zno.core.back.memory

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.core.uarch._
import zno.common._


class ZnoAgu(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val base = Input(UInt(p.xlen.W))
    val off  = Input(UInt(p.xlen.W))
    val addr = Output(UInt(p.xlen.W))
  })
  io.addr := io.base + io.off
}

class ZnoMemPipeline(implicit p: ZnoParam) extends Module {
}
