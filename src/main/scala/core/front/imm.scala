
package zno.core.front.decode.imm

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.common.bitpat._
import zno.riscv.isa._
import zno.core.uarch._
import zno.core.dispatch.RflAllocPort
import zno.core.rf.RFWritePort

// Generate the full sign-extended 32-bit value of some immediate. 
class ImmediateGenerator(implicit p: ZnoParam) extends Module {
  import RvEncType._
  val io = IO(new Bundle {
    val enc  = Input(RvEncType())     // Type of instruction encoding
    val data = Input(new RvImmData)   // Input immediate data
    val out  = Output(UInt(p.xlen.W)) // Output immediate data
  })

  val enc  = io.enc
  val sign = io.data.sign
  val imm  = io.data.imm
  io.out  := MuxCase(0.U, Seq(
    (enc === ENC_I) -> Cat(Fill(21, sign), imm(10, 0)),
    (enc === ENC_S) -> Cat(Fill(21, sign), imm(10, 0)),
    (enc === ENC_B) -> Cat(Fill(20, sign), imm(10, 0), 0.U(1.W)),
    (enc === ENC_U) -> Cat(sign, imm, Fill(12, 0.U)),
    (enc === ENC_J) -> Cat(Fill(12, sign), imm, 0.U(1.W)),
  ))
}


