package zno.core.back.issue

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.core.uarch._
import zno.common._

class WakeupReadPort(implicit p: ZnoParam) extends Bundle {
  val prn = Input(p.Prn())
  val rdy = Output(Bool())
}

class IssueWakeupArray(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val rp = Vec(4, new WakeupReadPort)
  })

  // A vector of bits (one for each physical register number)
  val arr = UInt(p.prf.size.W)

  for (rp <- io.rp) {
    val prn_oh = UIntToOH(rp.prn)
    rp.rdy := arr & prn_oh
  }


}
