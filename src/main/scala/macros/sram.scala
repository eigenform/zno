package zno.macros.sram

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource

class sky130_sram_1kbyte_1rw_32x256_8
  extends BlackBox with HasBlackBoxResource 
{
  val io = IO(new Bundle {
    val clk0        = Input(Clock())
    val csb0        = Input(Bool())      // active-low
    val web0        = Input(Bool())      // active-low
    val wmask0      = Input(UInt(4.W))
    val spare_wen0  = Input(Bool())      // active-low
    val addr0       = Input(UInt(9.W))
    val din0        = Input(UInt(33.W))
    val dout0       = Output(UInt(33.W))
  })
  addResource("/sky130A/sky130_sram_1kbyte_1rw_32x256_8.v")
}

class sky130_sram_2kbyte_1rw1r_32x512_8 
  extends BlackBox with HasBlackBoxResource 
{
  val io = IO(new Bundle {
    val clk0   = Input(Clock())
    val csb0   = Input(Bool())      // active-low
    val web0   = Input(Bool())      // active-low
    val wmask0 = Input(UInt(4.W))
    val addr0  = Input(UInt(9.W))
    val din0   = Input(UInt(32.W))
    val dout0  = Output(UInt(32.W))

    val clk1   = Input(Clock())
    val csb1   = Input(Bool())      // active-low
    val addr1  = Input(UInt(9.W))
    val dout1  = Output(UInt(32.W))
  })

  addResource("/sky130A/sky130_sram_2kbyte_1rw1r_32x512_8.v")

}
