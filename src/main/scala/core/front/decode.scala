
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
import zno.core.dispatch.RflAllocPort
import zno.core.rf.RFWritePort

// [RvInst] is an intermediate set of control signals that we want to associate
// to some [BitPat] matching each instruction. 
// See the definition for [Rv32iPattern] in 'zno.riscv.isa'.
//
// About RISC-V Instructions
// =========================
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

class RvInst(implicit p: ZnoParam) extends Bundle with AsBitPat {
  val kind  = UopKind()
  val enc   = RvEncType()       // The type of instruction encoding
  //val src   = SourceType()      // The set of source operands
  val rr    = Bool()            // Register result
  val ld_sext = Bool()
  val jmp_ind = Bool()
  val cond = ZnoBranchCond() // Associated control-flow opcode
  val aluop = ZnoAluOpcode()    // Associated ALU opcode
  val memw = ZnoLdstWidth()
}
object RvInst {
  def apply(
    kind: UopKind.Type,
    enc: RvEncType.Type, 
    rr: Bool,
    ld_sext: Bool,
    jmp_ind: Bool,
    cond: ZnoBranchCond.Type, 
    aluop: ZnoAluOpcode.Type, 
    memw: ZnoLdstWidth.Type,
  ): BitPat = {
    implicit val p = ZnoParam()
    (new RvInst).Lit(
      _.kind  -> kind,
      _.enc   -> enc,
      _.rr    -> rr,
      _.ld_sext -> ld_sext,
      _.jmp_ind -> jmp_ind,
      _.cond -> cond, 
      _.aluop -> aluop, 
      _.memw -> memw, 
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
  import RvEncType._

  import ZnoAluOpcode._
  import ZnoLdstWidth._
  import ZnoBranchCond._

  val N = false.B
  val Y = true.B
  val X = DontCare

  // A map from an instruction bitpattern to a set of control signals.
  val matches = Array(
    BEQ     -> RvInst(U_BRN, ENC_B, N, N, N, B_EQ,  A_EQ,   W_NOP),   
    BNE     -> RvInst(U_BRN, ENC_B, N, N, N, B_NEQ, A_NEQ,  W_NOP),
    BLT     -> RvInst(U_BRN, ENC_B, N, N, N, B_LT,  A_LT,   W_NOP),
    BGE     -> RvInst(U_BRN, ENC_B, N, N, N, B_GE,  A_GE,   W_NOP),
    BLTU    -> RvInst(U_BRN, ENC_B, N, N, N, B_LTU, A_LTU,  W_NOP),
    BGEU    -> RvInst(U_BRN, ENC_B, N, N, N, B_GEU, A_GEU,  W_NOP),

    JAL     -> RvInst(U_JMP, ENC_J, Y, N, N, B_NOP, A_NOP,  W_NOP),
    JALR    -> RvInst(U_JMP, ENC_I, Y, N, Y, B_NOP, A_NOP,  W_NOP),

    LUI     -> RvInst(U_INT, ENC_U, Y, N, N, B_NOP, A_ADD,  W_NOP),
    AUIPC   -> RvInst(U_INT, ENC_U, Y, N, N, B_NOP, A_ADD,  W_NOP),

    ADDI    -> RvInst(U_INT, ENC_I, Y, N, N, B_NOP, A_ADD,  W_NOP),
    SLTI    -> RvInst(U_INT, ENC_I, Y, N, N, B_NOP, A_LT,   W_NOP),
    SLTIU   -> RvInst(U_INT, ENC_I, Y, N, N, B_NOP, A_LTU,  W_NOP),
    XORI    -> RvInst(U_INT, ENC_I, Y, N, N, B_NOP, A_XOR,  W_NOP),
    ORI     -> RvInst(U_INT, ENC_I, Y, N, N, B_NOP, A_OR,   W_NOP),
    ANDI    -> RvInst(U_INT, ENC_I, Y, N, N, B_NOP, A_AND,  W_NOP),

    ADD     -> RvInst(U_INT, ENC_R, Y, N, N, B_NOP, A_ADD,  W_NOP),
    SUB     -> RvInst(U_INT, ENC_R, Y, N, N, B_NOP, A_SUB,  W_NOP),
    SLL     -> RvInst(U_INT, ENC_R, Y, N, N, B_NOP, A_SLL,  W_NOP),
    SLT     -> RvInst(U_INT, ENC_R, Y, N, N, B_NOP, A_LT,   W_NOP),
    SLTU    -> RvInst(U_INT, ENC_R, Y, N, N, B_NOP, A_LTU,  W_NOP),
    XOR     -> RvInst(U_INT, ENC_R, Y, N, N, B_NOP, A_XOR,  W_NOP),
    SRL     -> RvInst(U_INT, ENC_R, Y, N, N, B_NOP, A_SRL,  W_NOP),
    SRA     -> RvInst(U_INT, ENC_R, Y, N, N, B_NOP, A_SRA,  W_NOP),
    OR      -> RvInst(U_INT, ENC_R, Y, N, N, B_NOP, A_OR,   W_NOP),
    AND     -> RvInst(U_INT, ENC_R, Y, N, N, B_NOP, A_AND,  W_NOP),

    LB      -> RvInst(U_LD,  ENC_I, Y, Y, N, B_NOP, A_ADD,  W_B),
    LH      -> RvInst(U_LD,  ENC_I, Y, Y, N, B_NOP, A_ADD,  W_H),
    LW      -> RvInst(U_LD,  ENC_I, Y, N, N, B_NOP, A_ADD,  W_W),
    LBU     -> RvInst(U_LD,  ENC_I, Y, N, N, B_NOP, A_ADD,  W_B),
    LHU     -> RvInst(U_LD,  ENC_I, Y, N, N, B_NOP, A_ADD,  W_H),

    SB      -> RvInst(U_ST,  ENC_S, N, N, N, B_NOP, A_ADD,  W_B),
    SH      -> RvInst(U_ST,  ENC_S, N, N, N, B_NOP, A_ADD,  W_H),
    SW      -> RvInst(U_ST,  ENC_S, N, N, N, B_NOP, A_ADD,  W_W),
  )

  // The default set of control signals for instructions that do not match
  // any of the related bitpatterns.
  val default = {
        RvInst(U_NOP, ENC_ILL, N, N, N, B_NOP, A_NOP, W_NOP)
  }

  // Generate a decoder that maps an instruction to some [[RvInst]].
  // This uses logic minimization with ESPRESSO (which you'll probably need to
  // install and put in your $PATH, see chipsalliance/espresso; otherwise
  // I think Chisel will fall back to a different algorithm).
  //
  // If [[RvInst]] contains ChiselEnum objects, it will probably complain 
  // about casting non-literal UInts; I haven't thought hard enough about 
  // whether or not we should actually care about this. 
  def generate_decoder(inst: UInt): RvInst = {
    implicit val p = ZnoParam()
    assert(inst.getWidth == 32)
    chisel3.util.experimental.decode.decoder(inst,
      chisel3.util.experimental.decode.TruthTable(
        this.matches, this.default
      )
    ).asTypeOf(new RvInst)
  }
}

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

// Extracts an immediate value from a single 32-bit RISC-V instruction.
class ImmediateDecoder(implicit p: ZnoParam) extends Module {
  import RvEncType._
  val io   = IO(new Bundle {
    val enc  = Input(RvEncType())
    val inst = Input(UInt(p.xlen.W))
    val out  = Output(Valid(new RvImmData))
  })
  val inst = io.inst
  val enc  = io.enc

  val imm_i = Cat(Fill(9, 0.U), inst(30, 20))
  val imm_s = Cat(Fill(9, 0.U), inst(30, 25), inst(11, 7))
  val imm_b = Cat(Fill(9, 0.U), inst(7), inst(30, 25), inst(11, 8))
  val imm_u = inst(30, 12)
  val imm_j = Cat(inst(19, 12), inst(20), inst(30, 21))
  val imm   = MuxCase(0.U, Seq(
    (enc === ENC_I) -> imm_i,
    (enc === ENC_S) -> imm_s,
    (enc === ENC_B) -> imm_b,
    (enc === ENC_U) -> imm_u,
    (enc === ENC_J) -> imm_j,
  ))
  val len = PriorityEncoderHi(imm)
  io.out.valid     := (enc =/= ENC_ILL && enc =/= ENC_R)
  io.out.bits.sign := inst(31)
  io.out.bits.imm  := imm
  io.out.bits.inl  := (len <= p.pwidth.U)
}



// Transform a 32-bit RISC-V instruction encoding into a ZNO micro-op.
class ZnoUopDecoder(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val alc_rr  = Flipped(new RflAllocPort)
    val alc_imm = Flipped(new RflAllocPort)
    val rfwp    = Flipped(new RFWritePort) 
    val data    = Input(UInt(p.xlen.W))
    val uop     = Output(new Uop)
  })

  val inst   = DecoderTable.generate_decoder(io.data)
  val immdec = Module(new ImmediateDecoder)
  val immgen = Module(new ImmediateGenerator)
  immdec.io.enc  := inst.enc
  immdec.io.inst := io.data

  val has_imm     = immdec.io.out.valid

  immgen.io.enc  := inst.enc
  immgen.io.data := immdec.io.out.bits


  val rd        = io.data(11, 7)
  val rs1       = io.data(19, 15)
  val rs2       = io.data(24, 20)
  val rr        = (inst.rr) && (rd =/= 0.U)

  // Allocate a physical register for a register result
  io.uop.pd      := Mux(rr, io.alc_rr.idx.bits, 0.U)
  io.alc_rr.en   := rr

  // If an immediate can be inlined, drive it into ps3.
  // Otherwise, allocate a physical register.
  val inl_bits    = immdec.io.out.bits.imm(p.pwidth, 0)
  io.alc_imm.en  := !immdec.io.out.bits.inl
  io.uop.ps3     := Mux(immdec.io.out.bits.inl, inl_bits, io.alc_imm.idx.bits)

  // Write a non-inlined immediate to the physical register file
  io.rfwp.en     := !immdec.io.out.bits.inl
  io.rfwp.addr   := io.alc_imm.idx.bits
  io.rfwp.data   := immgen.io.out

  io.uop.kind    := inst.kind
  io.uop.enc     := inst.enc
  io.uop.aluop   := inst.aluop
  io.uop.cond    := inst.cond
  io.uop.memw    := inst.memw
  io.uop.rr      := rr
  io.uop.ld_sext := inst.ld_sext
  io.uop.jmp_ind := inst.jmp_ind

  io.uop.rid     := 0.U
  io.uop.rd      := rd
  io.uop.rs1     := rs1
  io.uop.rs2     := rs2
  io.uop.ps1     := 0.U
  io.uop.ps2     := 0.U

}


// Top-level decode stage logic.
//
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
//
class DecodeStage(implicit p: ZnoParam) extends Module {
  val req  = IO(Input(Vec(p.id_width, UInt(p.xlen.W))))
  val res  = IO(new FIFOProducerIO(new Uop, p.id_width))

  val alc_rr  = IO(Flipped(Vec(p.id_width, new RflAllocPort)))
  val alc_imm = IO(Flipped(Vec(p.id_width, new RflAllocPort)))
  val rfwp    = IO(Flipped(Vec(p.id_width, new RFWritePort)))

  // Output defaults
  for (idx <- 0 until p.id_width) {
    res.data(idx).drive_defaults()
  }
  res.len := 0.U

  val lim = res.lim

  val uop  = Wire(Vec(p.id_width, new Uop))

  // Instantiate decoders
  val uopdec = Seq.fill(p.id_width)(Module(new ZnoUopDecoder))
  for (idx <- 0 until p.id_width) {
    uopdec(idx).io.alc_rr  <> alc_rr(idx)
    uopdec(idx).io.alc_imm <> alc_imm(idx)
    uopdec(idx).io.rfwp    <> rfwp(idx)
    uopdec(idx).io.data := req(idx)

    uop(idx) := uopdec(idx).io.uop
    res.data(idx) := uop(idx)
  }


  res.len := p.id_width.U


}


