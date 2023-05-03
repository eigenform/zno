
package zno.core.front.predecode

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.common.bitpat._
import zno.riscv.isa._
import zno.core.uarch._

// [PredecodeTable] output
class PredecodeOutput extends Bundle with AsBitPat {
  val br_kind = BranchInstKind()
  val ifmt    = RvImmFmt()
}
object PdCtrl {
  def apply(br_kind: BranchInstKind.Type, ifmt: RvImmFmt.Type): BitPat = {
    (new PredecodeOutput).Lit(
      _.br_kind -> br_kind,
      _.ifmt   -> ifmt,
    ).to_bitpat()
  }
}

object PredecodeTable {
  import Rv32iPattern._
  import RvImmFmt._
  import BranchInstKind._

  val N = false.B
  val Y = true.B
  val X = DontCare

  // A map from an instruction bitpattern to a set of control signals.
  val matches = Array(
    JAL    -> PdCtrl(PB_JAL,  F_J),
    JALR   -> PdCtrl(PB_JALR, F_I),
    BEQ    -> PdCtrl(PB_BRN,  F_B),
    BGE    -> PdCtrl(PB_BRN,  F_B),
    BGEU   -> PdCtrl(PB_BRN,  F_B),
    BLT    -> PdCtrl(PB_BRN,  F_B),
    BLTU   -> PdCtrl(PB_BRN,  F_B),
    BNE    -> PdCtrl(PB_BRN,  F_B),
  )

  // The default set of control signals for instructions that do not match
  // any of the related bitpatterns.
  val default = {
              PdCtrl(PB_NONE, F_NA)
  }

  // NOTE: Chisel has some default logic minimzation, but I think this uses
  // chipsalliance/espresso if you have it in your $PATH. 
  def generate_decoder(inst: UInt): PredecodeOutput = {
    assert(inst.getWidth == 32)
    chisel3.util.experimental.decode.decoder(inst,
      chisel3.util.experimental.decode.TruthTable(
        this.matches, this.default
      )
    ).asTypeOf(new PredecodeOutput)
  }
}


// Instruction pre-decode logic. 
class Predecoder(implicit p: ZnoParam) extends Module {
  import RvImmFmt._
  import BranchInstKind._
  import BranchType._

  val io = IO(new Bundle {
    val opcd = Input(UInt(p.xlen.W))
    val out  = Output(new PdMop)
  })

  def is_lr(arn: UInt): Bool   = (arn === 1.U || arn === 5.U)
  def is_zero(arn: UInt): Bool = (arn === 0.U)

  val opcd    = io.opcd
  val pd_ctl  = PredecodeTable.generate_decoder(opcd)
  val rd      = opcd(11, 7)
  val rs1     = opcd(19, 15)

  val is_jal  = (pd_ctl.br_kind === PB_JAL)
  val is_jalr = (pd_ctl.br_kind === PB_JALR)
  val is_b    = (pd_ctl.br_kind === PB_BRN)
  val is_call = (is_jalr || is_jal) && is_lr(rd)
  val is_ret  = (is_jalr && is_lr(rs1))
  val btype   = MuxCase(BT_NONE, Seq(
    (is_call) -> BT_CALL,
    (is_ret ) -> BT_RET,
    (is_b)    -> BT_BRN,
    (is_jalr) -> BT_JALR,
    (is_jal)  -> BT_JAL,
  ))

  val imm_i = Cat(opcd(31, 20)) 
  val imm_b = Cat(opcd(31), opcd(7), opcd(30, 25), opcd(11, 8))
  val imm_j = Cat(opcd(31), opcd(19, 12), opcd(20), opcd(30, 25), opcd(24, 21))
  val imm   = MuxCase(0.U, Seq(
    (pd_ctl.ifmt === F_I) -> Sext(imm_i, 32),
    (pd_ctl.ifmt === F_B) -> Sext(Cat(imm_b, 0.U(1.W)), 32),
    (pd_ctl.ifmt === F_J) -> Sext(Cat(imm_j, 0.U(1.W)), 32),
  ))

  io.out.ifmt    := pd_ctl.ifmt
  io.out.imm     := imm
  io.out.opcd    := opcd
  io.out.btype   := btype
}


