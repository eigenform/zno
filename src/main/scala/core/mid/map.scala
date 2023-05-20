package zno.core.mid

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.riscv.isa._
import zno.core.uarch._

// Register map read port.
class MapReadPort(implicit p: ZnoParam) extends Bundle {
  val areg = Input(p.Arn())
  val preg = Output(p.Prn())
}

// Register map write port.
class MapWritePort(implicit p: ZnoParam) extends Bundle {
  val rd = Input(p.Arn())
  val pd = Input(p.Prn())
  val en = Input(Bool())
}

// Map from architectural registers to physical registers.
//
// FIXME: This is the "front" register map; you're also going to need another
// register map for the committed state. 
//
class RegisterMap(implicit p: ZnoParam) extends Module {
  val rp_rs1 = IO(Vec(p.dec_win.size, new MapReadPort))
  val rp_rs2 = IO(Vec(p.dec_win.size, new MapReadPort))
  val wp     = IO(Vec(p.dec_win.size, new MapWritePort))
  val zeroes = IO(Output(Vec(p.arf.size, Bool())))

  val regmap  = Reg(Vec(p.arf.size, p.Prn()))
  val is_zero = Reg(Vec(p.arf.size, Bool()))

  for (idx <- 0 until p.arf.size) {
    zeroes(idx) := is_zero(idx)
  }

  for (idx <- 0 until p.dec_win.size) {
    val rs1 = rp_rs1(idx).areg
    val rs2 = rp_rs2(idx).areg
    rp_rs1(idx).preg := Mux(rs1 === 0.U, 0.U, regmap(rs1))
    rp_rs2(idx).preg := Mux(rs2 === 0.U, 0.U, regmap(rs2))
  }

  for (idx <- 0 until p.dec_win.size) {
    when (wp(idx).en && wp(idx).rd =/= 0.U) {
      regmap(wp(idx).rd)  := wp(idx).pd
      is_zero(wp(idx).rd) := (wp(idx).pd === 0.U)
    }
  }
}


