
//package zno.riscv.top
//
//import chisel3._
//import chisel3.util._
//import chisel3.experimental.ChiselEnum
//
//import zno.riscv.hart._
//import zno.riscv.dbg._
//import zno.macros.sram._
//
//
//class Top extends Module {
//
//  val ibus = IO(new DebugBus)
//
//  // NOTE: This macro has an extra high bit 
//  val sram = Module(new sky130_sram_1kbyte_1rw_32x256_8)
//  val hart = Module(new Hart)
//
//  hart.ibus <> ibus
//
//  sram.io.clk0        := clock
//  sram.io.csb0        := !hart.dbus.ren
//  sram.io.web0        := !hart.dbus.wen
//  sram.io.spare_wen0  := true.B
//  sram.io.wmask0      := hart.dbus.wid.asTypeOf(UInt(4.W))
//  sram.io.addr0       := hart.dbus.addr
//  sram.io.din0        := Cat("b0".U(1.W), hart.dbus.wdata)
//  hart.dbus.data      := sram.io.dout0(31,0)
//
//
//}
