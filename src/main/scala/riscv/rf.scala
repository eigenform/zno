
package zno.riscv.rf

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

class RFReadPort extends Bundle {
  val addr = Input(UInt(5.W))
  val data = Output(UInt(32.W))

  def drive_defaults(): Unit = {
    this.addr := 0.U
  }
}

class RFWritePort extends Bundle {
  val addr = Input(UInt(5.W))
  val data = Input(UInt(32.W))
  val en   = Input(Bool())

  def drive_defaults(): Unit = {
    this.addr := 0.U
    this.data := 0.U
    this.en   := false.B
  }
}

class RegisterFileBRAM extends Module {
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
    reg.read(1.U), reg.read(2.U), reg.read(3.U), reg.read(4.U), reg.read(5.U), reg.read(6.U))
}

class RegisterFileFF extends Module {
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

