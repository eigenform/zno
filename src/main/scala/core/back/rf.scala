
package zno.core.back.rf

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.core.uarch._

// Generic register file read port
class RfReadPort(implicit p: ZnoParam) extends Bundle {
  val addr = Input(p.Prn())
  val data = Output(UInt(p.xlen.W))
  def drive_defaults(): Unit = {
    this.addr := 0.U
  }
}

// Generic register file write port
class RfWritePort(implicit p: ZnoParam) extends Bundle {
  val addr = Input(p.Prn())
  val data = Input(UInt(p.xlen.W))
  val en   = Input(Bool())
  def drive_defaults(): Unit = {
    this.addr := 0.U
    this.data := 0.U
    this.en   := false.B
  }
}

class RegisterFile(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val rp = Vec(2, new RfReadPort)
    val wp = Vec(3, new RfWritePort)
  })
  val reg = SyncReadMem(p.prf.size, UInt(32.W))
  for (wp <- io.wp)
    when (wp.en && wp.addr =/= 0.U) { 
      reg(wp.addr) := wp.data 
    }
  for (rp <- io.rp)
    rp.data := Mux(rp.addr === 0.U, 0.U, reg(rp.addr))

  printf("x1=%x x2=%x x3=%x x4=%x x5=%x x6=%x\n",
    reg(1), reg(2), reg(3), reg(4), reg(5), reg(6))

}

