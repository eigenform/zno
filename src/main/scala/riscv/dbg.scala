
package zno.riscv.dbg

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import chisel3.experimental.ChiselEnum

import zno.riscv.isa._
import zno.riscv.uarch._
import zno.riscv.hart._

// TODO: Replace with some kind of simple AXI SRAM

object DebugWidth extends ChiselEnum {
  val NONE = Value("b0000".U(4.W))
  val BYTE = Value("b0001".U(4.W))
  val HALF = Value("b0011".U(4.W))
  val WORD = Value("b1111".U(4.W))
}
class DebugBus extends Bundle {
  val wid   = Output(DebugWidth())
  val addr  = Output(UInt(32.W))
  val wdata = Output(UInt(32.W))
  val wen   = Output(Bool())
  val ren   = Output(Bool())
  val data  = Input(UInt(32.W))
}

class DebugROM(rom_file: String) extends Module {
  val ROM_SIZE: Int = 64
  val bus = IO(Flipped(new DebugBus))

  val rom = Mem(ROM_SIZE, UInt(32.W))
  if (rom_file.trim().nonEmpty) {
    loadMemoryFromFileInline(rom, rom_file)
  }
  bus.wid   := DontCare
  bus.wdata := DontCare
  bus.wen   := DontCare

  val rom_addr  = bus.addr((log2Ceil(ROM_SIZE) + 2), 2)
  bus.data     := rom.read(rom_addr)
}

class DebugRAM extends Module {
  val RAM_SIZE: Int = 1024
  val RAM_MASK: Int = (1 << log2Ceil(RAM_SIZE)) - 1
  val bus = IO(Flipped(new DebugBus))

  val mem = SyncReadMem(RAM_SIZE, Vec(4, UInt(8.W)))

  val addr = (bus.addr & RAM_MASK.U(32.W)) >> 2
  val data = Wire(Vec(4, UInt(8.W)))

  bus.data := 0.U
  data(0)  := 0.U
  data(1)  := 0.U
  data(2)  := 0.U
  data(3)  := 0.U

  data := mem.read(addr, !bus.wen)
  when (!bus.wen) {
    switch (bus.wid) {
      is (DebugWidth.NONE) { bus.data := 0.U }
      is (DebugWidth.BYTE) { bus.data := Cat(data(3), 0.U(8.W), 0.U(8.W), 0.U(8.W)) }
      is (DebugWidth.HALF) { bus.data := Cat(data(3), data(2), 0.U(8.W), 0.U(8.W)) }
      is (DebugWidth.WORD) { bus.data := Cat(data(3), data(2), data(1), data(0)) }
    }
  } .otherwise {
    mem.write(addr, bus.wdata.asTypeOf(Vec(4, UInt(8.W))), bus.wid.asUInt.asBools)
  }

}


class DebugOutput extends Bundle {
  val pc  = Output(UInt(32.W))
  val cyc = Output(UInt(32.W))
}


