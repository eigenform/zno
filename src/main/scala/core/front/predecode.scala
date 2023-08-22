
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

object PredecodeInstKind extends ChiselEnum {
  val PB_NONE = Value
  val PB_ILL  = Value
  val PB_JAL  = Value
  val PB_JALR = Value
  val PB_BRN  = Value
}

// [PredecodeTable] output
class PredecodeOutput extends Bundle with AsBitPat {
  val kind = PredecodeInstKind()
  val ifmt    = RvImmFmt()
}
object PdCtrl {
  def apply(kind: PredecodeInstKind.Type, ifmt: RvImmFmt.Type): BitPat = {
    (new PredecodeOutput).Lit(
      _.kind -> kind,
      _.ifmt   -> ifmt,
    ).to_bitpat()
  }
}

object PredecodeTable {
  import Rv32iPattern._
  import RvImmFmt._
  import PredecodeInstKind._

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

    LB     -> PdCtrl(PB_NONE, F_I),
    LH     -> PdCtrl(PB_NONE, F_I),
    LW     -> PdCtrl(PB_NONE, F_I),
    LBU    -> PdCtrl(PB_NONE, F_I),
    LHU    -> PdCtrl(PB_NONE, F_I),
    SB     -> PdCtrl(PB_NONE, F_S),
    SH     -> PdCtrl(PB_NONE, F_S),
    SW     -> PdCtrl(PB_NONE, F_S),
    AUIPC  -> PdCtrl(PB_NONE, F_U),
    LUI    -> PdCtrl(PB_NONE, F_U),
    ADD    -> PdCtrl(PB_NONE, F_NA),
    AND    -> PdCtrl(PB_NONE, F_NA),
    OR     -> PdCtrl(PB_NONE, F_NA),
    SLL    -> PdCtrl(PB_NONE, F_NA),
    SLT    -> PdCtrl(PB_NONE, F_NA),
    SLTU   -> PdCtrl(PB_NONE, F_NA),
    SRA    -> PdCtrl(PB_NONE, F_NA),
    SRL    -> PdCtrl(PB_NONE, F_NA),
    SUB    -> PdCtrl(PB_NONE, F_NA),
    XOR    -> PdCtrl(PB_NONE, F_NA),
    ADDI   -> PdCtrl(PB_NONE, F_I),
    ANDI   -> PdCtrl(PB_NONE, F_I),
    ORI    -> PdCtrl(PB_NONE, F_I),
    SLLI   -> PdCtrl(PB_NONE, F_I),
    SLTI   -> PdCtrl(PB_NONE, F_I),
    SLTIU  -> PdCtrl(PB_NONE, F_I),
    SRAI   -> PdCtrl(PB_NONE, F_I),
    SRLI   -> PdCtrl(PB_NONE, F_I),
    XORI   -> PdCtrl(PB_NONE, F_I),
    EBREAK -> PdCtrl(PB_NONE, F_NA),
    ECALL  -> PdCtrl(PB_NONE, F_NA),
    FENCE  -> PdCtrl(PB_NONE, F_NA),
  )

  // The default set of control signals for instructions that do not match
  // any of the related bitpatterns.
  val default = { PdCtrl(PB_ILL, F_NA) }

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
//
// The flow here is basically:
//  1. Determine the immediate format, and distinguish between branches, 
//     non-branches, and illegal instructions
//  2. Extract immediate control/data bits 
//
class Predecoder(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val opcd = Input(UInt(p.xlen.W))
    val out  = Output(new PdMop)
  })

  import RvImmFmt._
  import PredecodeInstKind._
  import BranchType._
  import BranchAddressingType._
  def is_lr(arn: UInt): Bool   = (arn === 1.U || arn === 5.U)
  def is_zero(arn: UInt): Bool = (arn === 0.U)

  // Simple decoder for distinguishing the immediate format/branch kind
  val pd_ctl  = PredecodeTable.generate_decoder(io.opcd)
  val rd      = io.opcd(11, 7)
  val rs1     = io.opcd(19, 15)

  val not_brn = (pd_ctl.kind === PB_NONE)
  val is_ill  = (pd_ctl.kind === PB_ILL)
  val is_b    = (pd_ctl.kind === PB_BRN)
  val is_jal  = (pd_ctl.kind === PB_JAL)
  val is_jalr = (pd_ctl.kind === PB_JALR)
  val is_call = (is_jalr || is_jal) && is_lr(rd)
  val is_ret  = (is_jalr && is_lr(rs1))

  // Extract immediate ctrl/data bits
  val immext   = Module(new ImmediateExtractor)
  val imm_data = immext.io.imm_data
  val imm_ctl  = immext.io.imm_ctl
  immext.io.ifmt := pd_ctl.ifmt
  immext.io.inst := io.opcd

  // Distinguish between different branch addressing modes
  val is_rel  = (is_b || is_jal)
  val is_ind  = (is_jalr)
  val is_dir  = (is_jalr && (rs1 === 0.U))
  val batype  = MuxCase(BA_NONE, Seq(
    (is_rel) -> BA_REL,
    (is_ind) -> BA_IND,
    (is_dir) -> BA_DIR,
  ))

  // Distinguish between different branches
  val btype = MuxCase(BT_NONE, Seq(
    (not_brn) -> BT_NONE,
    (is_b)    -> BT_BRN,
    (is_jal)  -> BT_JAL,
    (is_jalr) -> BT_JALR,
    (is_call) -> BT_CALL,
    (is_ret ) -> BT_RET,
  ))

  io.out.ill := is_ill
  io.out.imm_data := imm_data
  io.out.imm_ctl  := imm_ctl
  io.out.binfo.btype  := btype
  io.out.binfo.batype := batype

}


