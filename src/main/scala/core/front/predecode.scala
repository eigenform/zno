
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
import zno.core.front.decode.BrnOp
import zno.core.front.decode.JmpOp

// Different types of RISC-V branch instruction encodings. 
object BranchInstKind extends ChiselEnum {
  val PB_NONE = Value
  val PB_JAL  = Value
  val PB_JALR = Value
  val PB_BRN  = Value
}

// [PredecodeTable] output
class PdCtrl extends Bundle with AsBitPat {
  val br_kind = BranchInstKind()
  val ifmt    = ImmFmt()
}
object PdCtrl {
  def apply(
    br_kind: BranchInstKind.Type, ifmt: ImmFmt.Type, 
  ): BitPat = {
    (new PdCtrl).Lit(
      _.br_kind -> br_kind,
      _.ifmt   -> ifmt,
    ).to_bitpat()
  }
}

object PredecodeTable {
  import Rv32iPattern._
  import ImmFmt._
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
    //ADDI   -> PdCtrl(PB_NONE, F_I),
    //ANDI   -> PdCtrl(PB_NONE, F_I),
    //ORI    -> PdCtrl(PB_NONE, F_I),
    //SLLI   -> PdCtrl(PB_NONE, F_I),
    //SLTI   -> PdCtrl(PB_NONE, F_I),
    //SLTIU  -> PdCtrl(PB_NONE, F_I),
    //SRAI   -> PdCtrl(PB_NONE, F_I),
    //SRLI   -> PdCtrl(PB_NONE, F_I),
    //XORI   -> PdCtrl(PB_NONE, F_I),
    //LB     -> PdCtrl(PB_NONE, F_I),
    //LH     -> PdCtrl(PB_NONE, F_I),
    //LW     -> PdCtrl(PB_NONE, F_I),
    //LBU    -> PdCtrl(PB_NONE, F_I),
    //LHU    -> PdCtrl(PB_NONE, F_I),
    //SB     -> PdCtrl(PB_NONE, F_S),
    //SH     -> PdCtrl(PB_NONE, F_S),
    //SW     -> PdCtrl(PB_NONE, F_S),
    //AUIPC  -> PdCtrl(PB_NONE, F_U),
    //LUI    -> PdCtrl(PB_NONE, F_U),
  )

  // The default set of control signals for instructions that do not match
  // any of the related bitpatterns.
  val default = {
              PdCtrl(PB_NONE, F_NA)
  }

  // NOTE: Chisel has some default logic minimzation, but I think this uses
  // chipsalliance/espresso if you have it in your $PATH. 
  def generate_decoder(inst: UInt): PdCtrl = {
    assert(inst.getWidth == 32)
    chisel3.util.experimental.decode.decoder(inst,
      chisel3.util.experimental.decode.TruthTable(
        this.matches, this.default
      )
    ).asTypeOf(new PdCtrl)
  }
}


//// Different kinds of control-flow operations. 
//object BranchInstKind extends ChiselEnum {
//  val BK_NONE         = Value // No control-flow operation
//  val BK_COND_DIR_REL = Value // B{EQ,NE,LT,GT,LTU,GTU}
//
//  //val BK_COND_SQ_JMP  = Value // BEQ where rs1==rs2
//  //val BK_COND_SQ_NOP  = Value // BNE where rs1==rs2
//
//  val BK_JMP_DIR_REL  = Value // JAL where rd=x0
//  val BK_CALL_DIR_REL = Value // JAL where rd={x1,x5}
//
//  // Why should a compiler ever emit these? Ugh..
//  //val BK_CALL_UNK   = Value // JAL where rd=!{x1,x5}
//
//  val BK_JMP_DIR_ABS  = Value // JALR where rd=x0, rs1=x0
//  val BK_RET          = Value // JALR where rd=x0, rs1={x1,x5}
//  val BK_JMP_IND      = Value // JALR where rd=x0, rs1=!{x1,x5}
//  val BK_CALL_IND     = Value // JALR where rd={x1,x5}, rs1=!{x1,x5}
//
//  // Why should a compiler ever emit these? Ugh..
//  //val BK_CALL_UNK   = Value // JALR where rd=!{x1,x5}, rs1={x1,x5}
//  //val BK_CALL_UNK   = Value // JALR where rd=!{x1,x5}, rs1=!{x1,x5}
//
//}

class PdUop(implicit p: ZnoParam) extends Bundle {
  val opcd    = UInt(p.xlen.W)
  val btype   = BranchType()
  val imm     = UInt(p.xlen.W)
}


// Instruction pre-decode logic. 
class Predecoder(implicit p: ZnoParam) extends Module {
  import zno.riscv.isa.ImmFmt._
  import BranchInstKind._
  import BranchType._

  val io = IO(new Bundle {
    val opcd = Input(UInt(p.xlen.W))
    val out  = Output(new PdUop)
  })

  def is_lr(arn: UInt)   = (arn === 1.U || arn === 5.U)
  def is_zero(arn: UInt) = (arn === 0.U)

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

  io.out.imm     := imm
  io.out.opcd    := opcd
  io.out.btype   := btype
}

// Extracts an immediate value from a single 32-bit RISC-V instruction.
class ImmediateDecoder(implicit p: ZnoParam) extends Module {
  import ImmFmt._
  val io   = IO(new Bundle {
    val ifmt = Input(ImmFmt())
    val inst = Input(UInt(p.xlen.W))
    val out  = Output(Valid(new RvImmData))
  })
  val inst = io.inst

  val imm_i = Cat(Fill(9, 0.U), inst(30, 20))
  val imm_s = Cat(Fill(9, 0.U), inst(30, 25), inst(11, 7))
  val imm_b = Cat(Fill(9, 0.U), inst(7), inst(30, 25), inst(11, 8))
  val imm_u = inst(30, 12)
  val imm_j = Cat(inst(19, 12), inst(20), inst(30, 21))
  val imm   = MuxCase(0.U, Seq(
    (io.ifmt === F_I) -> imm_i,
    (io.ifmt === F_S) -> imm_s,
    (io.ifmt === F_B) -> imm_b,
    (io.ifmt === F_U) -> imm_u,
    (io.ifmt === F_J) -> imm_j,
  ))

  val is_zero = (imm === 0.U)
  val valid   = (io.ifmt =/= F_NA)

  val len     = Mux(is_zero, 0.U, ~PriorityEncoder(imm.asBools.reverse))
  val can_inl = (len <= p.pwidth.U)

  io.out.valid    := valid
  io.out.bits.ctl := MuxCase(UopImmCtl.NONE, Seq(
    (valid && is_zero)              -> UopImmCtl.ZERO,
    (valid && !is_zero && can_inl)  -> UopImmCtl.INL,
    (valid && !is_zero && !can_inl) -> UopImmCtl.ALC,
  ))
  io.out.bits.sign := inst(31)
  io.out.bits.imm  := imm
}


