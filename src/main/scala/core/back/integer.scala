package zno.core.back.integer

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.core.uarch._
import zno.common._

class ZnoAlu(implicit p: ZnoParam) extends Module {
  import ZnoAluOpcode._
  val io = IO(new Bundle { 
    val op  = Input(ZnoAluOpcode())
    val x   = Input(UInt(p.xlen.W))
    val y   = Input(UInt(p.xlen.W))
    val res = Output(UInt(p.xlen.W))
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
    is (A_EQ )  { tmp := x === y }
    is (A_NEQ)  { tmp := x =/= y }
    is (A_LT)   { tmp := (x.asSInt < y.asSInt).asUInt }
    is (A_GE )  { tmp := (x.asSInt >= y.asSInt).asUInt }
    is (A_LTU)  { tmp := x < y }
    is (A_GEU)  { tmp := x >= y }
  }
  io.res := tmp
}
