package zno.core.front.cfm

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._

class CfmEntry(implicit p: ZnoParam) extends Bundle {
  val foo   = Bool()
}
class CfmWritePort(implicit p: ZnoParam) extends Bundle {
  val en    = Input(Bool())
  val addr  = Input(UInt(p.xlen.W))
  val data  = Input(new CfmEntry)
}
class CfmReadPort(implicit p: ZnoParam) extends Bundle {
  val en    = Input(Bool())
  val addr  = Input(UInt(p.xlen.W))
  val data  = Output(new CfmEntry)
}


// Tracking for in-flight basic-blocks. 
class ControlFlowMap(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val wp = Input(Vec(1, new CfmWritePort))
    val rp = Input(Vec(1, new CfmReadPort))
  })
  val tag   = Reg(Vec(p.cfm_sz, UInt(p.cfm_tag_width.W)))
  val entry = Reg(Vec(p.cfm_sz, new CfmEntry))

  for (wp <- io.wp) {
  }
  for (rp <- io.rp) {
  }
}



