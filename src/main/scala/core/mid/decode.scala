
package zno.core.mid

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.common.bitpat._
import zno.riscv.isa._
import zno.core.uarch._

// Output from a single decoder
class DecoderOutput extends Bundle with AsBitPat {
  val kind    = UopKind()
  val ifmt    = RvImmFmt()
  val brn_op  = BrnOp()
  val mem_w   = LdStWidth()
  val ld_sext = Bool()
  val rr      = Bool()
  val alu_op  = AluOp()
  val src1    = SrcType()
  val src2    = SrcType()
}

object Mop {
  def apply(
    kind: UopKind.Type, ifmt: RvImmFmt.Type, 
    brn_op: BrnOp.Type, mem_w:  LdStWidth.Type, 
    ld_sext: Bool, rr: Bool,
    alu_op: AluOp.Type,
    src1: SrcType.Type, src2: SrcType.Type,
  ): BitPat = {
    (new DecoderOutput).Lit(
      _.kind   -> kind,   _.ifmt    -> ifmt,
      _.brn_op  -> brn_op, _.mem_w  -> mem_w,
      _.ld_sext -> ld_sext, _.rr -> rr,
      _.alu_op -> alu_op,
      _.src1 -> src1, _.src2 -> src2, 
    ).to_bitpat()
  }
}

object DecoderTable {
  import Rv32iPattern._
  import UopKind._
  import RvImmFmt._
  import BrnOp._
  import LdStWidth._
  import AluOp._
  import SrcType._

  val N = false.B
  val Y = true.B
  val X = DontCare

  // A map from an instruction bitpattern to a set of control signals.
  val matches = Array(
    JAL    -> Mop(U_JMP, F_J,  B_NOP, W_NOP, N, Y, A_NOP,  S_NONE, S_NONE),
    JALR   -> Mop(U_JMP, F_I,  B_NOP, W_NOP, N, Y, A_NOP,  S_NONE, S_NONE),
 
    BEQ    -> Mop(U_BRN, F_B,  B_EQ,  W_NOP, N, N, A_NOP,  S_NONE, S_NONE),
    BGE    -> Mop(U_BRN, F_B,  B_GE,  W_NOP, N, N, A_NOP,  S_NONE, S_NONE),
    BGEU   -> Mop(U_BRN, F_B,  B_GEU, W_NOP, N, N, A_NOP,  S_NONE, S_NONE),
    BLT    -> Mop(U_BRN, F_B,  B_LT,  W_NOP, N, N, A_NOP,  S_NONE, S_NONE),
    BLTU   -> Mop(U_BRN, F_B,  B_LTU, W_NOP, N, N, A_NOP,  S_NONE, S_NONE),
    BNE    -> Mop(U_BRN, F_B,  B_NE,  W_NOP, N, N, A_NOP,  S_NONE, S_NONE),
   
    LB     -> Mop(U_LD,  F_I,  B_NOP, W_B,   N, Y, A_NOP,  S_REG,  S_IMM),
    LH     -> Mop(U_LD,  F_I,  B_NOP, W_H,   N, Y, A_NOP,  S_REG,  S_IMM),
    LW     -> Mop(U_LD,  F_I,  B_NOP, W_W,   N, Y, A_NOP,  S_REG,  S_IMM),

    LBU    -> Mop(U_LD,  F_I,  B_NOP, W_B,   Y, Y, A_NOP,  S_REG,  S_IMM),
    LHU    -> Mop(U_LD,  F_I,  B_NOP, W_H,   Y, Y, A_NOP,  S_REG,  S_IMM),
    
    SB     -> Mop(U_ST,  F_S,  B_NOP, W_B,   N, N, A_NOP,  S_REG,  S_IMM),
    SH     -> Mop(U_ST,  F_S,  B_NOP, W_H,   N, N, A_NOP,  S_REG,  S_IMM),
    SW     -> Mop(U_ST,  F_S,  B_NOP, W_W,   N, N, A_NOP,  S_REG,  S_IMM),
    
    AUIPC  -> Mop(U_INT, F_U,  B_NOP, W_NOP, N, Y, A_ADD,  S_PC,   S_IMM),
    LUI    -> Mop(U_INT, F_U,  B_NOP, W_NOP, N, Y, A_ADD,  S_ZERO, S_IMM),
    
    ADD    -> Mop(U_INT, F_NA, B_NOP, W_NOP, N, Y, A_ADD,  S_REG,  S_REG),
    AND    -> Mop(U_INT, F_NA, B_NOP, W_NOP, N, Y, A_AND,  S_REG,  S_REG),
    OR     -> Mop(U_INT, F_NA, B_NOP, W_NOP, N, Y, A_OR,   S_REG,  S_REG),
    SLL    -> Mop(U_INT, F_NA, B_NOP, W_NOP, N, Y, A_SLL,  S_REG,  S_REG),
    SLT    -> Mop(U_INT, F_NA, B_NOP, W_NOP, N, Y, A_SLT,  S_REG,  S_REG),
    SLTU   -> Mop(U_INT, F_NA, B_NOP, W_NOP, N, Y, A_SLTU, S_REG,  S_REG),
    SRA    -> Mop(U_INT, F_NA, B_NOP, W_NOP, N, Y, A_SRA,  S_REG,  S_REG),
    SRL    -> Mop(U_INT, F_NA, B_NOP, W_NOP, N, Y, A_SRL,  S_REG,  S_REG),
    SUB    -> Mop(U_INT, F_NA, B_NOP, W_NOP, N, Y, A_SUB,  S_REG,  S_REG),
    XOR    -> Mop(U_INT, F_NA, B_NOP, W_NOP, N, Y, A_XOR,  S_REG,  S_REG),
    
    ADDI   -> Mop(U_INT, F_I,  B_NOP, W_NOP, N, Y, A_ADD,  S_REG,  S_IMM),
    ANDI   -> Mop(U_INT, F_I,  B_NOP, W_NOP, N, Y, A_AND,  S_REG,  S_IMM),
    ORI    -> Mop(U_INT, F_I,  B_NOP, W_NOP, N, Y, A_OR,   S_REG,  S_IMM),
    SLLI   -> Mop(U_INT, F_I,  B_NOP, W_NOP, N, Y, A_SLL,  S_REG,  S_IMM),
    SLTI   -> Mop(U_INT, F_I,  B_NOP, W_NOP, N, Y, A_SLT,  S_REG,  S_IMM),
    SLTIU  -> Mop(U_INT, F_I,  B_NOP, W_NOP, N, Y, A_SLTU, S_REG,  S_IMM),
    SRAI   -> Mop(U_INT, F_I,  B_NOP, W_NOP, N, Y, A_SRA,  S_REG,  S_IMM),
    SRLI   -> Mop(U_INT, F_I,  B_NOP, W_NOP, N, Y, A_SRL,  S_REG,  S_IMM),
    XORI   -> Mop(U_INT, F_I,  B_NOP, W_NOP, N, Y, A_XOR,  S_REG,  S_IMM),
    
    EBREAK -> Mop(U_ILL, F_NA, B_NOP, W_NOP, N, N, A_NOP,  S_NONE, S_NONE),
    ECALL  -> Mop(U_ILL, F_NA, B_NOP, W_NOP, N, N, A_NOP,  S_NONE, S_NONE),
    FENCE  -> Mop(U_ILL, F_NA, B_NOP, W_NOP, N, N, A_NOP,  S_NONE, S_NONE),
  )

  // The default set of control signals for instructions that do not match
  // any of the related bitpatterns.
  val default = {
              Mop(U_ILL, F_NA, B_NOP, W_NOP, N, N, A_NOP,  S_NONE, S_NONE)
  }

  // NOTE: Chisel has some default logic minimzation, but I think this uses
  // chipsalliance/espresso if you have it in your $PATH. 
  def generate_decoder(inst: UInt): DecoderOutput = {
    assert(inst.getWidth == 32)
    chisel3.util.experimental.decode.decoder(inst,
      chisel3.util.experimental.decode.TruthTable(
        this.matches, this.default
      )
    ).asTypeOf(new DecoderOutput)
  }
}



class UopDecoder(implicit p: ZnoParam) extends Module {
  import RvImmFmt._
  val io = IO(new Bundle {
    val inst = Input(UInt(p.xlen.W))
    val out  = Output(new MacroOp)
  })

  val inst = io.inst
  val out  = io.out
  val tbl  = DecoderTable.generate_decoder(inst)

  val rd  = inst(11, 7)
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)

  // NOTE: You're handling this elsewhere
  //val immext = Module(new ImmediateExtractor)
  //immext.io.inst := inst
  //immext.io.ifmt := tbl.ifmt
  //out.imm_data := immext.io.imm_data
  //out.imm_ctl  := immext.io.imm_ctl

  out.kind     := tbl.kind
  out.brn_op   := tbl.brn_op
  out.mem_w    := tbl.mem_w
  out.ld_sext  := tbl.ld_sext
  out.rr       := tbl.rr
  out.alu_op   := tbl.alu_op
  out.src1     := tbl.src1
  out.src2     := tbl.src2
  out.rd       := rd
  out.rs1      := rs1
  out.rs2      := rs2

  // NOTE: This is computed during rename
  out.mov_ctl  := MovCtl.NONE

}


