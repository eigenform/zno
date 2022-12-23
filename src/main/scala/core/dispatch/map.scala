
package zno.core.dispatch

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.riscv.isa._
import zno.core.uarch._

// Register map read port.
class MapReadPort(implicit p: ZnoParam) extends Bundle {
  val areg = Input(UInt(p.awidth.W))
  val preg = Output(UInt(p.pwidth.W))
}

// Register map write port.
class MapWritePort(implicit p: ZnoParam) extends Bundle {
  val rd = Input(UInt(p.awidth.W))
  val pd = Input(UInt(p.pwidth.W))
  val en = Input(Bool())
}



// Map from architectural registers to physical registers.
//
// FIXME: This is the "front" register map; you're also going to need another
// register map for the committed state. 
//
class RegisterMap(implicit p: ZnoParam) extends Module {
  val rp     = IO(Vec((p.id_width * 2), new MapReadPort))
  val wp     = IO(Vec(p.id_width, new MapWritePort))
  val regmap = Reg(Vec(p.num_areg, UInt(p.pwidth.W)))

  val zeroes = IO(Output(UInt(p.num_areg.W)))
  val is_z   = regmap.map(preg => preg === 0.U)
  zeroes    := OHToUInt(is_z)

  // Defaults
  for (port <- rp) {
    port.preg := 0.U
  }
  // Connect read ports
  for (port <- rp) {
    port.preg := Mux(port.areg =/= 0.U, regmap(port.areg), 0.U)
  }
  // Connect write ports
  for (port <- wp) {
    when (port.en && port.rd =/= 0.U) {
      regmap(port.rd) := port.pd
    }
  }
}


