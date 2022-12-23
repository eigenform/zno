
package zno.core.rf

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.core.uarch._

// Generic register file read port
class RFReadPort(implicit p: ZnoParam) extends Bundle {
  val addr = Input(UInt(p.pwidth.W))
  val data = Output(UInt(p.xlen.W))
  def drive_defaults(): Unit = {
    this.addr := 0.U
  }
}

// Generic register file write port
class RFWritePort(implicit p: ZnoParam) extends Bundle {
  val addr = Input(UInt(p.pwidth.W))
  val data = Input(UInt(p.xlen.W))
  val en   = Input(Bool())
  def drive_defaults(): Unit = {
    this.addr := 0.U
    this.data := 0.U
    this.en   := false.B
  }
}

// NOTE: You should try to use this version if you're targeting FPGA.
class RegisterFileBRAM(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val rp = Vec(2, new RFReadPort)
    val wp = Vec(3, new RFWritePort)
  })
  val reg = Mem(32, UInt(32.W))
  for (wp <- io.wp) {
    when (wp.en && wp.addr =/= 0.U) { 
      reg.write(wp.addr, wp.data) 
    }
  }
  for (rp <- io.rp) {
    rp.data := Mux(rp.addr === 0.U, 0.U, reg.read(rp.addr))
  }
  printf("x1=%x x2=%x x3=%x x4=%x x5=%x x6=%x\n",
    reg.read(1.U), reg.read(2.U), reg.read(3.U), reg.read(4.U), 
    reg.read(5.U), reg.read(6.U)
  )
}

// NOTE: If you're planning on exploring synthesis for ASICs, you might want 
// to explore smarter ways of compiling a register file for whatever cell
// library you're targeting. Otherwise, there are massive area/power/routing 
// requirements when a large multi-port register file is synthesized into a 
// huge blob of DFF stdcells.

class RegisterFileFF(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val rp = Vec(2, new RFReadPort)
    val wp = Vec(3, new RFWritePort)
  })
  val reg = Reg(Vec(32, UInt(32.W)))
  for (wp <- io.wp)
    when (wp.en && wp.addr =/= 0.U) { 
      reg(wp.addr) := wp.data 
    }
  for (rp <- io.rp)
    rp.data := Mux(rp.addr === 0.U, 0.U, reg(rp.addr))

  printf("x1=%x x2=%x x3=%x x4=%x x5=%x x6=%x\n",
    reg(1), reg(2), reg(3), reg(4), reg(5), reg(6))

}

