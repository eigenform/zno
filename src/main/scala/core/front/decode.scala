
package zno.core.front.decode

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.common.bitpat._
import zno.riscv.isa._
import zno.core.uarch._
import zno.core.front.decode.imm._
import zno.core.dispatch.RflAllocPort
import zno.core.rf.RFWritePort

// Decode stage logic.
//
// About RISC-V Instructions
// =========================
//
// The base ISA distinguishes between the following primitive operations:
//
//  - ALU operations
//  - Load operations
//  - Store operations
//  - Conditional branch operations
//  - Unconditional branch (jump) operations 
//
// In general, the RISC-V base ISA makes a distinction between (a) integer
// instructions, (b) branch instructions, and (c) load/store instructions. 
// There's a natural distinction between: 
//
//  - A control-flow pipeline performing instruction fetch transactions
//  - An integer pipeline performing ALU operations
//  - A memory pipeline performing memory transactions
//
// All three kinds of instructions rely on an ALU operation, for instance:
//
//  - Integer instructions always use an ALU
//  - Load/store instructions use an ALU for address generation
//  - Branch instructions use an ALU to evaluate a condition, and to
//    generate a target address
//
// Huge List of Tiny Complaints about RISC-V Encodings
// ===================================================
//
// - "rd=x0 may appear in encodings that have no architectural effect without 
//   a destination register"
// - "Call/return aren't completely distinguished from JAL/JALR"
//
// Handling Immediate Values
// =========================
//
// You'd *like* immediates to live in the PRF if they need to, but the
// physical registers allocated for immediates will never be reused! 
// When the instruction window is full of operations with immediates which
// cannot be inlined, you're creating lots of pressure on the PRF.
//
// You might consider a dedicated immediate register file, but how do you
// deal with addressing? I guess you could use the reorder buffer index, 
// and have a register file that covers the size of the instruction window?
// (But in that case, you'll want to do all of this after ROB allocation
// instead of during the decode stage).


object JmpOp extends ChiselEnum {
  val J_NOP = Value
  val J_IND = Value // Indirect [absolute]
  val J_DIR = Value // Direct [PC-relative]
}
object BrnOp extends ChiselEnum {
  val B_NOP = Value
  val B_EQ  = Value
  val B_NE  = Value
  val B_LT  = Value
  val B_GE  = Value
  val B_LTU = Value
  val B_GEU = Value
}
object LdStWidth extends ChiselEnum {
  val W_NOP = Value
  val W_B   = Value
  val W_H   = Value
  val W_W   = Value
}

object AluOp extends ChiselEnum {
  val A_NOP  = Value
  val A_ADD  = Value
  val A_SUB  = Value
  val A_SLL  = Value
  val A_SLT  = Value
  val A_SLTU = Value
  val A_XOR  = Value
  val A_SRL  = Value
  val A_SRA  = Value
  val A_OR   = Value
  val A_AND  = Value
}
object AluSrcType extends ChiselEnum {
  val S_xxxx  = Value
  val S_RRxx  = Value // RS1 and RS2
  val S_RxIx  = Value // RS1 and imm12
  val S_xxIP  = Value // imm20 and PC (auipc)
  val S_xxIx  = Value // imm20 (lui)
}

class OpCtl extends Bundle with AsBitPat {
  val kind    = UopKind()
  val ifmt    = ImmFmt()
  val brn_op  = BrnOp()
  val jmp_op  = JmpOp()
  val mem_w   = LdStWidth()
  val ld_sext = Bool()
  val rr      = Bool()
  val alu_op  = AluOp()
  val alu_src = AluSrcType()

  val src1    = SrcType()
  val src2    = SrcType()

}
object OpCtl {
  def apply(
    kind: UopKind.Type, ifmt: ImmFmt.Type, 
    jmp_op: JmpOp.Type, brn_op: BrnOp.Type,
    mem_w:  LdStWidth.Type, ld_sext: Bool, rr: Bool,
    alu_op: AluOp.Type, alu_src: AluSrcType.Type, 
    src1: SrcType.Type, src2: SrcType.Type,
  ): BitPat = {
    (new OpCtl).Lit(
      _.kind   -> kind,   _.ifmt    -> ifmt,
      _.jmp_op -> jmp_op, _.brn_op  -> brn_op,
      _.mem_w  -> mem_w,  _.ld_sext -> ld_sext, _.rr -> rr,
      _.alu_op -> alu_op, _.alu_src -> alu_src,
      _.src1 -> src1, _.src2 -> src2, 
    ).to_bitpat()
  }
}


// This object is used to generate a RISC-V instruction decoder. 
//
// NOTE: It's unfortunate that the signals used to distinguish the type of
// immediate aren't distict from everything else, otherwise you could do
// immediate decoding perfectly in parallel?
//
object DecoderTable {
  import Rv32iPattern._
  import UopKind._
  import ImmFmt._
  import JmpOp._
  import BrnOp._
  import LdStWidth._
  import AluOp._
  import SrcType._
  import AluSrcType._

  val N = false.B
  val Y = true.B
  val X = DontCare

  // A map from an instruction bitpattern to a set of control signals.
  val matches = Array(
    JAL    -> OpCtl(U_JMP, F_J,  J_DIR, B_NOP, W_NOP, N, Y, A_NOP,  S_xxxx, S_NONE, S_NONE),
    JALR   -> OpCtl(U_JMP, F_I,  J_IND, B_NOP, W_NOP, N, Y, A_NOP,  S_xxxx, S_NONE, S_NONE),
 
    BEQ    -> OpCtl(U_BRN, F_B,  J_NOP, B_EQ,  W_NOP, N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
    BGE    -> OpCtl(U_BRN, F_B,  J_NOP, B_GE,  W_NOP, N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
    BGEU   -> OpCtl(U_BRN, F_B,  J_NOP, B_GEU, W_NOP, N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
    BLT    -> OpCtl(U_BRN, F_B,  J_NOP, B_LT,  W_NOP, N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
    BLTU   -> OpCtl(U_BRN, F_B,  J_NOP, B_LTU, W_NOP, N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
    BNE    -> OpCtl(U_BRN, F_B,  J_NOP, B_NE,  W_NOP, N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
   
    LB     -> OpCtl(U_LD,  F_I,  J_NOP, B_NOP, W_B,   N, Y, A_NOP,  S_xxxx, S_NONE, S_NONE),
    LH     -> OpCtl(U_LD,  F_I,  J_NOP, B_NOP, W_H,   N, Y, A_NOP,  S_xxxx, S_NONE, S_NONE),
    LW     -> OpCtl(U_LD,  F_I,  J_NOP, B_NOP, W_W,   N, Y, A_NOP,  S_xxxx, S_NONE, S_NONE),
    LBU    -> OpCtl(U_LD,  F_I,  J_NOP, B_NOP, W_B,   Y, Y, A_NOP,  S_xxxx, S_NONE, S_NONE),
    LHU    -> OpCtl(U_LD,  F_I,  J_NOP, B_NOP, W_H,   Y, Y, A_NOP,  S_xxxx, S_NONE, S_NONE),
    
    SB     -> OpCtl(U_ST,  F_S,  J_NOP, B_NOP, W_B,   N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
    SH     -> OpCtl(U_ST,  F_S,  J_NOP, B_NOP, W_H,   N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
    SW     -> OpCtl(U_ST,  F_S,  J_NOP, B_NOP, W_W,   N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
    
    AUIPC  -> OpCtl(U_INT, F_U,  J_NOP, B_NOP, W_NOP, N, Y, A_ADD,  S_xxIP, S_IMM,  S_PC),
    LUI    -> OpCtl(U_INT, F_U,  J_NOP, B_NOP, W_NOP, N, Y, A_ADD,  S_xxIx, S_IMM,  S_ZERO),
    
    ADD    -> OpCtl(U_INT, F_NA, J_NOP, B_NOP, W_NOP, N, Y, A_ADD,  S_RRxx, S_REG,  S_REG),
    AND    -> OpCtl(U_INT, F_NA, J_NOP, B_NOP, W_NOP, N, Y, A_AND,  S_RRxx, S_REG,  S_REG),
    OR     -> OpCtl(U_INT, F_NA, J_NOP, B_NOP, W_NOP, N, Y, A_OR,   S_RRxx, S_REG,  S_REG),
    SLL    -> OpCtl(U_INT, F_NA, J_NOP, B_NOP, W_NOP, N, Y, A_SLL,  S_RRxx, S_REG,  S_REG),
    SLT    -> OpCtl(U_INT, F_NA, J_NOP, B_NOP, W_NOP, N, Y, A_SLT,  S_RRxx, S_REG,  S_REG),
    SLTU   -> OpCtl(U_INT, F_NA, J_NOP, B_NOP, W_NOP, N, Y, A_SLTU, S_RRxx, S_REG,  S_REG),
    SRA    -> OpCtl(U_INT, F_NA, J_NOP, B_NOP, W_NOP, N, Y, A_SRA,  S_RRxx, S_REG,  S_REG),
    SRL    -> OpCtl(U_INT, F_NA, J_NOP, B_NOP, W_NOP, N, Y, A_SRL,  S_RRxx, S_REG,  S_REG),
    SUB    -> OpCtl(U_INT, F_NA, J_NOP, B_NOP, W_NOP, N, Y, A_SUB,  S_RRxx, S_REG,  S_REG),
    XOR    -> OpCtl(U_INT, F_NA, J_NOP, B_NOP, W_NOP, N, Y, A_XOR,  S_RRxx, S_REG,  S_REG),
    
    ADDI   -> OpCtl(U_INT, F_I,  J_NOP, B_NOP, W_NOP, N, Y, A_ADD,  S_RxIx, S_REG,  S_IMM),
    ANDI   -> OpCtl(U_INT, F_I,  J_NOP, B_NOP, W_NOP, N, Y, A_AND,  S_RxIx, S_REG,  S_IMM),
    ORI    -> OpCtl(U_INT, F_I,  J_NOP, B_NOP, W_NOP, N, Y, A_OR,   S_RxIx, S_REG,  S_IMM),
    SLLI   -> OpCtl(U_INT, F_I,  J_NOP, B_NOP, W_NOP, N, Y, A_SLL,  S_RxIx, S_REG,  S_IMM),
    SLTI   -> OpCtl(U_INT, F_I,  J_NOP, B_NOP, W_NOP, N, Y, A_SLT,  S_RxIx, S_REG,  S_IMM),
    SLTIU  -> OpCtl(U_INT, F_I,  J_NOP, B_NOP, W_NOP, N, Y, A_SLTU, S_RxIx, S_REG,  S_IMM),
    SRAI   -> OpCtl(U_INT, F_I,  J_NOP, B_NOP, W_NOP, N, Y, A_SRA,  S_RxIx, S_REG,  S_IMM),
    SRLI   -> OpCtl(U_INT, F_I,  J_NOP, B_NOP, W_NOP, N, Y, A_SRL,  S_RxIx, S_REG,  S_IMM),
    XORI   -> OpCtl(U_INT, F_I,  J_NOP, B_NOP, W_NOP, N, Y, A_XOR,  S_RxIx, S_REG,  S_IMM),
    
    EBREAK -> OpCtl(U_ILL, F_NA, J_NOP, B_NOP, W_NOP, N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
    ECALL  -> OpCtl(U_ILL, F_NA, J_NOP, B_NOP, W_NOP, N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
    FENCE  -> OpCtl(U_ILL, F_NA, J_NOP, B_NOP, W_NOP, N, N, A_NOP,  S_xxxx, S_NONE, S_NONE),
  )

  // The default set of control signals for instructions that do not match
  // any of the related bitpatterns.
  val default = {
              OpCtl(U_ILL, F_NA, J_NOP, B_NOP, W_NOP, N, N, A_NOP,  S_xxxx, S_NONE, S_NONE)
  }

  // NOTE: Chisel has some default logic minimzation, but I think this uses
  // chipsalliance/espresso if you have it in your $PATH. 
  def generate_decoder(inst: UInt): OpCtl = {
    assert(inst.getWidth == 32)
    chisel3.util.experimental.decode.decoder(inst,
      chisel3.util.experimental.decode.TruthTable(
        this.matches, this.default
      )
    ).asTypeOf(new OpCtl)
  }
}


//// Transform a 32-bit RISC-V instruction encoding into a ZNO micro-op.
//class ZnoUopDecoder(implicit p: ZnoParam) extends Module {
//  import zno.core.uarch.UopKind._
//  import zno.core.uarch.ZnoAluOpcode._
//  val io = IO(new Bundle {
//    val data = Input(UInt(p.xlen.W))
//    val uop  = Output(new Uop)
//  })
//
//  val inst   = DecoderTable.generate_decoder(io.data)
//  val immdec = Module(new ImmediateDecoder)
//  immdec.io.enc  := inst.enc
//  immdec.io.inst := io.data
//
//  val rd        = io.data(11, 7)
//  val rs1       = io.data(19, 15)
//  val rs2       = io.data(24, 20)
//  val has_rr    = (inst.rr) && (rd =/= 0.U)
//
//  // addi rd, rs1, #0  (mov rd, rs1)
//  val mov_rs1_i = (inst.aluop == A_ADD) && 
//                  (immdec.io.out.ctl === UopImmCtl.ZERO) && 
//                  (rs1 =/= 0.U)
//
//  // NOTE: Cases where an instruction can be squashed into a 'MOV' operation:
//  // addi rd, x0, #imm (mov rd, #imm)
//  //  - lui rd, #imm      (mov rd, #imm)
//  //  - add rd, rs1, x0   (mov rd, rs1)
//  //  - sub rd, rs1, x0   (mov rd, rs1)
//  //  - add rd, x0, rs2   (mov rd, rs2)
//
//  io.uop.kind    := inst.kind
//  io.uop.enc     := inst.enc
//  io.uop.aluop   := inst.aluop
//  io.uop.cond    := inst.cond
//  io.uop.memw    := inst.memw
//  io.uop.ld_sext := inst.ld_sext
//  io.uop.jmp_ind := inst.jmp_ind
//  io.uop.rr      := has_rr
//
//  io.uop.rid     := 0.U
//  io.uop.rd      := rd
//  io.uop.rs1     := rs1
//  io.uop.rs2     := rs2
//  io.uop.pd      := 0.U
//  io.uop.ps1     := 0.U
//  io.uop.ps2     := 0.U
//  io.uop.ps3     := 0.U
//
//}

// Extracts an immediate value from a single 32-bit RISC-V instruction.
class ImmediateDecoder(implicit p: ZnoParam) extends Module {
  import ImmFmt._
  val io   = IO(new Bundle {
    val ifmt = Input(ImmFmt())
    val inst = Input(UInt(p.xlen.W))
    val out  = Output(new RvImmData)
    val ctl  = Output(UopImmCtl())
    val sign = Output(Bool())
    val imm  = Output(UInt(19.W))
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

  val len     = Mux(is_zero, 0.U, PriorityEncoderHi(imm))
  val can_inl = (len <= p.pwidth.U)

  io.out.ctl := MuxCase(UopImmCtl.NONE, Seq(
    (valid && is_zero)              -> UopImmCtl.ZERO,
    (valid && !is_zero && can_inl)  -> UopImmCtl.INL,
    (valid && !is_zero && !can_inl) -> UopImmCtl.ALC,
  ))
  io.out.sign := inst(31)
  io.out.imm  := imm
}


object MovCtl extends ChiselEnum {
  val NONE = Value
  val SRC1 = Value // Move src1
  val SRC2 = Value // Move src2
}

class UopCtl(implicit p: ZnoParam) extends Bundle {
  val kind    = UopKind()
  val ifmt    = ImmFmt()
  val brn_op  = BrnOp()
  val jmp_op  = JmpOp()
  val mem_w   = LdStWidth()
  val ld_sext = Bool()
  val rr      = Bool()
  val alu_op  = AluOp()
  val alu_src = AluSrcType()
  val src1    = SrcType()
  val src2    = SrcType()

  val rr_alc  = Bool()
  val agu_alc = Bool()

  val imm_ctl = UopImmCtl()
  val imm_sign = Bool()
  val imm_data = UInt(19.W)
  val mov_ctl = MovCtl()

  val rd      = UInt(p.awidth.W)
  val rs1     = UInt(p.awidth.W)
  val rs2     = UInt(p.awidth.W)
}


class UopDecoder(implicit p: ZnoParam) extends Module {
  import AluOp._
  import AluSrcType._
  import ImmFmt._

  val inst    = IO(Input(UInt(p.xlen.W)))
  val uop_ctl = IO(Output(new UopCtl))
  val op_ctl  = DecoderTable.generate_decoder(inst)

  // Architectural registers
  val rd      = inst(11, 7)
  val rs1     = inst(19, 15)
  val rs2     = inst(24, 20)

  // "Do we need to allocate a result register?"
  val rr_alc  = (op_ctl.rr) && (rd =/= 0.U)
  val agu_alc = (op_ctl.kind === UopKind.U_LD)

  // Pull out immediate data and control signals
  val imm_i = Cat(Fill(9, 0.U), inst(30, 20))
  val imm_s = Cat(Fill(9, 0.U), inst(30, 25), inst(11, 7))
  val imm_b = Cat(Fill(9, 0.U), inst(7), inst(30, 25), inst(11, 8))
  val imm_u = inst(30, 12)
  val imm_j = Cat(inst(19, 12), inst(20), inst(30, 21))
  val imm_data = MuxCase(0.U, Seq(
    (op_ctl.ifmt === F_I) -> imm_i,
    (op_ctl.ifmt === F_S) -> imm_s,
    (op_ctl.ifmt === F_B) -> imm_b,
    (op_ctl.ifmt === F_U) -> imm_u,
    (op_ctl.ifmt === F_J) -> imm_j
  ))
  val imm_sign  = inst(31)
  val imm_zero  = (imm_data === 0.U)
  val imm_valid = (op_ctl.ifmt =/= F_NA)
  val imm_len   = Mux(imm_zero, 0.U, PriorityEncoderHi(imm_data))
  val imm_inl   = (imm_len <= p.pwidth.U)
  val imm_ctl   = MuxCase(UopImmCtl.NONE, Seq(
    (imm_valid && imm_zero)              -> UopImmCtl.ZERO,
    (imm_valid && !imm_zero && imm_inl)  -> UopImmCtl.INL,
    (imm_valid && !imm_zero && !imm_inl) -> UopImmCtl.ALC,
  ))

  // Determine if an ALU operation satisfies mov/zero idiom conditions
  val add  = (op_ctl.alu_op === A_ADD)
  val or   = (op_ctl.alu_op === A_OR)
  val sub  = (op_ctl.alu_op === A_SUB)
  val xor  = (op_ctl.alu_op === A_XOR)
  val and  = (op_ctl.alu_op === A_AND)
  val rs1_zero = (rs1 === 0.U)
  val rs2_zero = (rs2 === 0.U)
  val src1_zero = MuxCase(false.B, Seq(
    ((op_ctl.src1 === SrcType.S_REG) && (rs1_zero)) -> true.B,
    ((op_ctl.src1 === SrcType.S_IMM) && (imm_zero)) -> true.B,
  ))
  val src2_zero = MuxCase(false.B, Seq(
    ((op_ctl.src2 === SrcType.S_REG) && (rs2_zero)) -> true.B,
    ((op_ctl.src2 === SrcType.S_IMM) && (imm_zero)) -> true.B,
    (op_ctl.src2 === SrcType.S_ZERO)                -> true.B,
  ))
  val mov_src1 = ((add || or || sub) && (src2_zero))
  val mov_src2 = ((add || or) && (src1_zero))
  val mov_ctl  = MuxCase(MovCtl.NONE, Seq(
    (mov_src1) -> MovCtl.SRC1,
    (mov_src2) -> MovCtl.SRC2,
  ))
  val mov_zero = (
    // '{and,andi} rd, rs1, {x0, #0}'
    (and && src2_zero)
  ||
    // '{xor,sub} rd, rN, rN'
    ((xor || sub) &&
    ((op_ctl.src1 === SrcType.S_REG) && (op_ctl.src1 === SrcType.S_REG)) &&
    (rs1 === rs2))
  ||
    // 'mov rd, rs1=0'
    (mov_src1 && (src1_zero))
  ||
    // 'mov rd, rs2=0'
    (mov_src2 && (src2_zero))
  )


  uop_ctl.kind    := op_ctl.kind
  uop_ctl.ifmt    := op_ctl.ifmt
  uop_ctl.brn_op  := op_ctl.brn_op
  uop_ctl.jmp_op  := op_ctl.jmp_op
  uop_ctl.mem_w   := op_ctl.mem_w
  uop_ctl.ld_sext := op_ctl.ld_sext
  uop_ctl.rr      := op_ctl.rr
  uop_ctl.alu_op  := op_ctl.alu_op
  uop_ctl.alu_src := op_ctl.alu_src
  uop_ctl.src1    := op_ctl.src1
  uop_ctl.src2    := op_ctl.src2

  uop_ctl.imm_ctl  := imm_ctl
  uop_ctl.imm_data := imm_data
  uop_ctl.imm_sign := imm_sign
  uop_ctl.mov_ctl  := mov_ctl

  uop_ctl.rr_alc  := rr_alc
  uop_ctl.agu_alc := agu_alc

  uop_ctl.rd      := rd
  uop_ctl.rs1     := rs1
  uop_ctl.rs2     := rs2

}

class DecodeStage(implicit p: ZnoParam) extends Module {
  val req  = IO(Input(Vec(p.id_width, UInt(p.xlen.W))))
  val res  = IO(new FIFOProducerIO(new UopCtl, p.id_width))

  res.len := 0.U
  val lim  = res.lim

  val uopdec = Seq.fill(p.id_width)(Module(new UopDecoder))
  for (idx <- 0 until p.id_width) {
    uopdec(idx).inst := req(idx)
    res.data(idx)    := uopdec(idx).uop_ctl
  }


}


