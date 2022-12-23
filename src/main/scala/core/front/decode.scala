
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
  val enc   = RvEncType()       // The type of instruction encoding
  //val src   = SourceType()      // The set of source operands
  val rr    = Bool()            // Register result
  val brnop = ZnoBranchOpcode() // Associated control-flow opcode
  val aluop = ZnoAluOpcode()    // Associated ALU opcode
  val memop = ZnoLdstOpcode()   // Associated memory opcode
}
object RvInst {
  def apply(
    enc: RvEncType.Type, 
    //src: SourceType.Type,
    rr: Bool,
    brnop: ZnoBranchOpcode.Type, 
    aluop: ZnoAluOpcode.Type, 
    memop: ZnoLdstOpcode.Type,
  ): BitPat = {
    implicit val p = ZnoParam()
    (new RvInst).Lit(
      _.enc   -> enc,
      //_.src   -> src, 
      _.rr    -> rr,
      _.brnop -> brnop, 
      _.aluop -> aluop, 
      _.memop -> memop, 
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
  import ExecutionUnit._
  import RvEncType._
  import ZnoAluOpcode._
  import ZnoLdstOpcode._
  import ZnoBranchOpcode._
  //import SourceType._

  val N = false.B
  val Y = true.B

  // A map from an instruction bitpattern to a set of control signals.
  val matches = Array(
    BEQ     -> RvInst(ENC_B, N, B_BRN, A_EQ,   M_NOP),   
    BNE     -> RvInst(ENC_B, N, B_BRN, A_NEQ,  M_NOP),
    BLT     -> RvInst(ENC_B, N, B_BRN, A_LT,   M_NOP),
    BGE     -> RvInst(ENC_B, N, B_BRN, A_GE,   M_NOP),
    BLTU    -> RvInst(ENC_B, N, B_BRN, A_LTU,  M_NOP),
    BGEU    -> RvInst(ENC_B, N, B_BRN, A_GEU,  M_NOP),

    JAL     -> RvInst(ENC_J, Y, B_JMP, A_NOP,  M_NOP),
    JALR    -> RvInst(ENC_I, Y, B_JMP, A_NOP,  M_NOP),

    LUI     -> RvInst(ENC_U, Y, B_NOP, A_ADD,  M_NOP),
    AUIPC   -> RvInst(ENC_U, Y, B_NOP, A_ADD,  M_NOP),

    ADDI    -> RvInst(ENC_I, Y, B_NOP, A_ADD,  M_NOP),
    SLTI    -> RvInst(ENC_I, Y, B_NOP, A_LT,  M_NOP),
    SLTIU   -> RvInst(ENC_I, Y, B_NOP, A_LTU, M_NOP),
    XORI    -> RvInst(ENC_I, Y, B_NOP, A_XOR,  M_NOP),
    ORI     -> RvInst(ENC_I, Y, B_NOP, A_OR,   M_NOP),
    ANDI    -> RvInst(ENC_I, Y, B_NOP, A_AND,  M_NOP),

    ADD     -> RvInst(ENC_R, Y, B_NOP, A_ADD,  M_NOP),
    SUB     -> RvInst(ENC_R, Y, B_NOP, A_SUB,  M_NOP),
    SLL     -> RvInst(ENC_R, Y, B_NOP, A_SLL,  M_NOP),
    SLT     -> RvInst(ENC_R, Y, B_NOP, A_LT,  M_NOP),
    SLTU    -> RvInst(ENC_R, Y, B_NOP, A_LTU, M_NOP),
    XOR     -> RvInst(ENC_R, Y, B_NOP, A_XOR,  M_NOP),
    SRL     -> RvInst(ENC_R, Y, B_NOP, A_SRL,  M_NOP),
    SRA     -> RvInst(ENC_R, Y, B_NOP, A_SRA,  M_NOP),
    OR      -> RvInst(ENC_R, Y, B_NOP, A_OR,   M_NOP),
    AND     -> RvInst(ENC_R, Y, B_NOP, A_AND,  M_NOP),

    LB      -> RvInst(ENC_I, Y, B_NOP, A_ADD,  M_LB),
    LH      -> RvInst(ENC_I, Y, B_NOP, A_ADD,  M_LH),
    LW      -> RvInst(ENC_I, Y, B_NOP, A_ADD,  M_LW),
    LBU     -> RvInst(ENC_I, Y, B_NOP, A_ADD,  M_LBU),
    LHU     -> RvInst(ENC_I, Y, B_NOP, A_ADD,  M_LHU),

    SB      -> RvInst(ENC_S, N, B_NOP, A_ADD,  M_SB),
    SH      -> RvInst(ENC_S, N, B_NOP, A_ADD,  M_SH),
    SW      -> RvInst(ENC_S, N, B_NOP, A_ADD,  M_SW),
  )

  // The default set of control signals for instructions that do not match
  // any of the related bitpatterns.
  val default = {
        RvInst(ENC_ILL, N, B_NOP, A_NOP, M_NOP)
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

// Describing an immediate value extracted from a RISC-V instruction.
//
///Immediate values from decoded instructions are either 12-bit or 20-bit. 
// The type of instrucion encoding indicates how the immediate should be 
// expanded into the sign-extended 32-bit value. 
//
// Storage for Immediate Values
// ============================
//
// Immediate values occur very often in an instruction stream. 
// This is unfortunate because the cost of moving the fully-expanded immediate 
// bits down the pipeline (ie. within the associated micro-op) is quite high: 
// in that situation, immediate storage is distributed all over the machine. 
//
// In one sense, it'd be nice to keep immediates in the physical register file
// so that micro-ops only consist of *names* of source operands. 
// This probably involves allocating from the PRF and writing at decode-time.
//
// Trivial immediate values (zero, or encodings in very few bits) occur 
// very often in an instruction stream. The cost of tracking these can be
// mitigated somewhat at decode-time: if an immediate value can be described
// in at most 'log2(prf_size)' bits, then we can "inline" the bits into a
// physical source register name instead of consuming space in the physical
// register file. 
//
class RvImmData(implicit p: ZnoParam) extends Bundle {
  val imm    = UInt(19.W) // Immediate low bits
  val sign   = Bool()     // Immediate high/sign bit
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
  //val enc  = IO(Input(RvEncType()))
  //val inst = IO(Input(UInt(p.xlen.W)))
  //val out  = IO(Output(Valid(new RvImmData)))

  val imm  = MuxCase(0.U, Seq(
    (enc === ENC_I) -> Cat(Fill(9, 0.U), inst(30, 20)),
    (enc === ENC_S) -> Cat(Fill(9, 0.U), inst(30, 25), inst(11, 7)),
    (enc === ENC_B) -> Cat(Fill(9, 0.U), inst(7), inst(30, 25), inst(11, 8)),
    (enc === ENC_U) -> inst(30, 12),
    (enc === ENC_J) -> Cat(inst(19, 12), inst(20), inst(30, 21)),
  ))
  io.out.valid     := (enc =/= ENC_R) && (enc =/= ENC_ILL)
  io.out.bits.sign := inst(31)
  io.out.bits.imm  := imm
}


// Generate the full sign-extended 32-bit value of some immediate. 
class ImmediateGenerator(implicit p: ZnoParam) extends Module {
  import RvEncType._
  val io = IO(new Bundle {
    val enc  = Input(RvEncType())          // Type of instruction encoding
    val data = Input(Valid(new RvImmData)) // Immediate data
    val out  = Output(Valid(UInt(p.xlen.W)))
  })

  val res  = WireDefault(0.U(p.xlen.W))
  val enc  = io.enc
  val sign = io.data.bits.sign
  val imm  = io.data.bits.imm
  io.out.valid := io.data.valid
  io.out.bits  := MuxCase(0.U, Seq(
    (enc === ENC_I) -> Cat(Fill(21, sign), imm(10, 0)),
    (enc === ENC_S) -> Cat(Fill(21, sign), imm(10, 0)),
    (enc === ENC_B) -> Cat(Fill(20, sign), imm(10, 0), 0.U(1.W)),
    (enc === ENC_U) -> Cat(sign, imm, Fill(12, 0.U)),
    (enc === ENC_J) -> Cat(Fill(12, sign), imm, 0.U(1.W)),
  ))
}



// Transform a 32-bit RISC-V instruction encoding into a ZNO micro-op.
class ZnoUopDecoder(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val data = Input(UInt(p.xlen.W))
    val uop  = Output(new Uop)
  })
  io.uop.drive_defaults()

  val inst   = DecoderTable.generate_decoder(io.data)

  //val immdec = Module(new ImmediateDecoder)
  //val immgen = Module(new ImmediateGenerator)
  //immdec.io.enc  := inst.enc
  //immdec.io.inst := io.data
  //val immlen  = PriorityEncoderHi(immdec.io.out.bits.imm)
  //val imm_inl = (immlen <= p.pwidth.U)

  val rd        = io.data(11, 7)
  val rs1       = io.data(19, 15)
  val rs2       = io.data(24, 20)
  val rr        = (inst.rr) && (rd =/= 0.U)

  io.uop.rid     := 0.U
  io.uop.rr      := rr
  io.uop.aluop   := inst.aluop
  io.uop.brnop   := inst.brnop
  io.uop.memop   := inst.memop
  io.uop.rd      := rd
  io.uop.rs1     := rs1
  io.uop.rs2     := rs2
  io.uop.pd      := 0.U
  io.uop.ps1     := 0.U
  io.uop.ps2     := 0.U
  io.uop.ps3     := 0.U
}


class DecodeStage(implicit p: ZnoParam) extends Module {
  val req = IO(Input(Vec(p.id_width, UInt(p.xlen.W))))
  val res = IO(new FIFOProducerIO(new Uop, p.id_width))

  // Output defaults
  for (idx <- 0 until p.id_width) {
    res.data(idx).drive_defaults()
  }
  res.len := 0.U

  val lim = res.lim

  val uop  = Wire(Vec(p.id_width, new Uop))

  // Instantiate decoders
  val decoder = Seq.fill(p.id_width)(Module(new ZnoUopDecoder))
  for (idx <- 0 until p.id_width) {
    decoder(idx).io.data := req(idx)
    uop(idx) := decoder(idx).io.uop
  }


  res.len := p.id_width.U

}










