
package zno.core.front

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.common.bitpat._
import zno.riscv.isa._
import zno.core.uarch._

// Generate the full sign-extended 32-bit value of some immediate operand.
class ImmediateGenerator(implicit p: ZnoParam) extends Module {
  import RvImmFmt._
  val io = IO(new Bundle {
    val enc  = Input(RvImmFmt())
    val data = Input(new RvImmData)
    val out  = Output(UInt(p.xlen.W))
  })

  val enc  = io.enc
  val sign = io.data.sign
  val imm  = io.data.imm
  io.out  := MuxCase(0.U, Seq(
    (enc === F_I) -> Cat(Fill(21, sign), imm(10, 0)),
    (enc === F_S) -> Cat(Fill(21, sign), imm(10, 0)),
    (enc === F_B) -> Cat(Fill(20, sign), imm(10, 0), 0.U(1.W)),
    (enc === F_U) -> Cat(sign, imm, Fill(12, 0.U)),
    (enc === F_J) -> Cat(Fill(12, sign), imm, 0.U(1.W)),
  ))
}

// Extract immediate control/data bits from a single 32-bit RISC-V instruction.
class ImmediateExtractor(implicit p: ZnoParam) extends Module {
  import RvImmFmt._
  val io   = IO(new Bundle {
    val ifmt     = Input(RvImmFmt())
    val inst     = Input(UInt(p.xlen.W))
    val imm_data = Output(new RvImmData)
    val imm_ctl  = Output(new ImmCtl)
  })

  val inst  = io.inst
  val ifmt  = io.ifmt

  // Obtain the actual data associated with this immediate. 
  val sign  = inst(31)
  val imm_i = Cat(Fill(9, 0.U), inst(30, 20))
  val imm_s = Cat(Fill(9, 0.U), inst(30, 25), inst(11, 7))
  val imm_b = Cat(Fill(9, 0.U), inst(7), inst(30, 25), inst(11, 8))
  val imm_u = inst(30, 12)
  val imm_j = Cat(inst(19, 12), inst(20), inst(30, 21))
  val imm   = MuxCase(0.U, Seq(
    (ifmt === F_I) -> imm_i,
    (ifmt === F_S) -> imm_s,
    (ifmt === F_B) -> imm_b,
    (ifmt === F_U) -> imm_u,
    (ifmt === F_J) -> imm_j,
  ))
  io.imm_data.sign := sign
  io.imm_data.imm  := imm

  // Control signals used to determine how immediate data is stored/expanded
  // elsewhere in the machine.
  //
  // The immediate format bits [RvImmFmt] determine how the immediate should
  // be expanded into the full 32-bit value. 
  //
  // Storage for immediates is determined by the following procedure:
  //
  // 1. If no bits are set, the immediate is zero
  // 2. Get the index of the highest set bit
  // 3. If the index of the highest set bit is less-than-or-equal-to the 
  //    bitwidth of a physical register index, the immediate can be stored
  //    inline within a micro-op
  // 4. Otherwise, the immediate must live in some centralized storage
  //

  val valid   = (ifmt =/= F_NA)
  val is_zero = Mux(valid, (imm === 0.U), true.B)
  val immlen  = Mux(is_zero, 0.U, OHToUInt(imm))
  val can_inl = (immlen <= p.prf.idxWidth.U)
  val storage = MuxCase(ImmStorageKind.NONE, Seq(
    (valid && is_zero)              -> ImmStorageKind.ZERO,
    (valid && !is_zero && can_inl)  -> ImmStorageKind.INL,
    (valid && !is_zero && !can_inl) -> ImmStorageKind.ALC,
  ))
  io.imm_ctl.ifmt     := ifmt
  io.imm_ctl.storage  := storage
}


