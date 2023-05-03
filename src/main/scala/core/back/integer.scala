package zno.core.back.integer

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.core.uarch._
import zno.core.back.rf._


// After passing thru the decode units, operands for integer ops 

class IntegerOperandCtl(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val uop = Input(new IntUop)
    val rp  = Vec(2, Flipped(new RfReadPort))
  })
  io.rp(0).drive_defaults()
  io.rp(1).drive_defaults()

}


class IntegerDatapath(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle { 
    val rp  = Vec(2, Flipped(new RfReadPort))
    val uop = Input(new IntUop)
  })

  val alu = Module(new Alu)

  io.rp(0).addr := io.uop.ps1
  io.rp(1).addr := io.uop.ps2


  alu.op := io.uop.alu_op
  alu.x  := io.rp(0).data
  alu.y  := io.rp(1).data


}

class Alu(implicit p: ZnoParam) extends Module {
  import AluOp._
  val io = IO(new Bundle { 
    val op  = Input(AluOp())
    val x   = Input(UInt(p.xlen.W))
    val y   = Input(UInt(p.xlen.W))
    val res = Output(Valid(UInt(p.xlen.W)))
  })

  io.res   := 0.U
  val tmp   = WireDefault(0.U(32.W))
  val op    = io.op
  val x     = io.x
  val y     = io.y
  val shamt = y(4, 0).asUInt

  switch (op) {
    is (A_ADD)  { tmp := x + y }
    is (A_SUB)  { tmp := x - y }
    is (A_AND)  { tmp := x & y }
    is (A_OR)   { tmp := x | y }
    is (A_XOR)  { tmp := x ^ y }
    is (A_SLL)  { tmp := x << shamt }
    is (A_SRL)  { tmp := x >> shamt }
    is (A_SRA)  { tmp := (x.asSInt >> shamt).asUInt }
    is (A_SLT)  { tmp := (x.asSInt < y.asSInt).asUInt }
    is (A_SLTU) { tmp := (x < y).asUInt }
  }
  io.res.bits  := tmp
  io.res.valid := (op =/= A_NOP)

}
