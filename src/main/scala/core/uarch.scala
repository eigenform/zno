package zno.core.uarch

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._

import zno.riscv.isa._

// Variables used to parameterize different aspects of the ZNO core. 
case class ZnoParam(
  xlen:     Int = 32, // Register width (in bits)
  num_areg: Int = 32, // Architectural Register File size
  num_preg: Int = 64, // Physical Register File size

  rob_sz:   Int = 32, // Reorder Buffer size
  sch_sz:   Int = 8,  // Scheduler Queue size

  fbq_sz:   Int = 4,  // Fetch Block Queue size
  ftq_sz:   Int = 4,  // Fetch Target Queue size
  cfm_sz:   Int = 8,  // Control-flow Map size

  opq_sz:   Int = 32, // Micro-op Queue size
  id_width: Int = 8,  // Decode window size
) {
  val fetch_bytes: Int = 32               // Bytes in a fetch block
  val fetch_words: Int = fetch_bytes / 4  // 32-bit words in a fetch block

  // Number of bits sufficient for representing a fetch block address
  // (addresses aligned to 'fetch_bytes')
  val cfm_tag_width: Int = xlen - log2Ceil(fetch_bytes)

  val robwidth: Int = log2Ceil(rob_sz)   // Reorder buffer index width
  val awidth:   Int = log2Ceil(num_areg) // Architectural register index width
  val pwidth:   Int = log2Ceil(num_preg) // Physical register index width
}

//abstract class ZnoBundle extends Bundle {
//}
//abstract class ZnoModule extends Bundle {
//}

// ----------------------------------------------------------------------------
// Micro-op/control-signal definitions

object ImmFmt extends ChiselEnum {
  val F_NA = Value
  val F_I  = Value
  val F_S  = Value
  val F_B  = Value
  val F_U  = Value
  val F_J  = Value
}

// Indicates a type of execution unit used to perform an operation.
//object ExecutionUnit extends ChiselEnum {
//  val EU_ILL = Value // No execution unit (illegal instruction)
//  val EU_NOP = Value // No execution unit (explicit no-op)
//  val EU_ALU = Value // ALU instruction
//  val EU_LSU = Value // Load/store instruction
//  val EU_BCU = Value // Branch/jump/compare instruction
//}

object ZnoAluOpcode extends ChiselEnum {
  val A_NOP  = Value
  val A_ADD  = Value
  val A_SUB  = Value
  val A_SLL  = Value
  val A_XOR  = Value
  val A_SRL  = Value
  val A_SRA  = Value
  val A_OR   = Value
  val A_AND  = Value
  val A_EQ   = Value
  val A_NEQ  = Value
  val A_LT   = Value
  val A_LTU  = Value
  val A_GE   = Value
  val A_GEU  = Value
  val A_ILL  = Value
}

//object ZnoLdstOpcode extends ChiselEnum {
//  val M_NOP = Value
//  val M_LB  = Value
//  val M_LH  = Value
//  val M_LW  = Value
//  val M_LBU = Value
//  val M_LHU = Value
//  val M_SB  = Value
//  val M_SH  = Value
//  val M_SW  = Value
//  val M_ILL = Value
//}

object ZnoBranchCond extends ChiselEnum {
  val B_NOP = Value
  val B_EQ  = Value
  val B_NEQ = Value
  val B_LT  = Value
  val B_LTU = Value
  val B_GE  = Value
  val B_GEU = Value
}

object ZnoLdstWidth extends ChiselEnum {
  val W_NOP = Value
  val W_B   = Value
  val W_H   = Value
  val W_W   = Value
}


// Describes the kinds of source operands associated with an instruction. 
// The encoding type (at least, for RV32I) mostly captures the kinds of source
// operands for an instruction:
//
//  - R-type encodings depend on RS1 and RS2
//  - I-type encodings depend on RS1 and an immediate value
//    - JALR *also* depends on the program counter
//  - S-type encodings depend on RS1, RS2, and an immediate value
//  - B-type encodings depend on RS1, RS2, an immediate value, and the 
//    program counter
//  - U-type encodings depend on an immediate value 
//    - AUIPC *also* depends on the program counter
//  - J-type encodings depend on an immediate value and the program counter
//
//object SourceType extends ChiselEnum {
//  val ST_NONE = Value("b0000".U) // Unused/dont-care
//  val ST_RRxx = Value("b1100".U) // RS1, RS2, (), ()   (R-type)
//  val ST_RxxI = Value("b1001".U) // RS1, (),  (), IMM  (I-type)
//  val ST_RxPI = Value("b1011".U) // RS1, (),  PC, IMM  (JALR)
//  val ST_RRxI = Value("b1101".U) // RS1, RS2, (), IMM  (S-type)
//  val ST_RRPI = Value("b1111".U) // RS1, RS2, PC, IMM  (B-type)
//  val ST_xxPI = Value("b0011".U) // (),  (),  PC, IMM  (J-type, AUIPC)
//  val ST_xxxI = Value("b0001".U) // (),  (),  (), IMM  (LUI)
//}





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
  val imm    = UInt(19.W)  // Immediate low bits
  val sign   = Bool()      // Immediate high/sign bit
  val ctl    = UopImmCtl() // Immediate control

  def drive_defaults(): Unit = {
    this.imm  := 0.U
    this.sign := false.B
    this.ctl  := UopImmCtl.NONE
  }
}

object SrcType extends ChiselEnum {
  val S_NONE = Value
  val S_ZERO = Value
  val S_REG  = Value
  val S_IMM  = Value
  val S_PC   = Value
}

object UopKind extends ChiselEnum {
  val U_ILL = Value
  val U_INT = Value
  val U_LD  = Value
  val U_ST  = Value
  val U_BRN = Value
  val U_JMP = Value
}


// Integer micro-op
class IntUop(implicit p: ZnoParam) extends Bundle {
  val alu_op  = ZnoAluOpcode()
  val rid     = UInt(p.robwidth.W) // Reorder buffer index
  val pd      = UInt(p.pwidth.W)   // Physical destination

  val ps1     = UInt(p.pwidth.W)   // Physical source #1
  val ps2     = UInt(p.pwidth.W)   // Physical source #2
  val imm_inl = Bool()
  val imm_s   = Bool()
  val use_pc  = Bool()
}

// Load micro-op
class LdUop(implicit p: ZnoParam) extends Bundle {
  val accw    = ZnoLdstWidth()     // Memory access width
  val sext    = Bool()             // 32-bit sign-extended result
  val rid     = UInt(p.robwidth.W) // Reorder buffer index
  val pd      = UInt(p.pwidth.W)   // Physical destination

  val ps_base = UInt(p.pwidth.W)   // Physical source (address base)
  val ps_imm  = UInt(p.pwidth.W)   // Immediate (added to ps_base)
  val imm_inl = Bool()
  val imm_s   = Bool()
}

// Store micro-op
class StUop(implicit p: ZnoParam) extends Bundle {
  val accw    = ZnoLdstWidth()     // Memory access width
  val rid     = UInt(p.robwidth.W) // Reorder buffer index

  val ps_data = UInt(p.pwidth.W)   // Physical source (data)
  val ps_base = UInt(p.pwidth.W)   // Physical source (address base)
  val ps_imm  = UInt(p.pwidth.W)   // Immediate (added to ps_base)
  val imm_inl = Bool()
  val imm_s   = Bool()
}

// Branch micro-op
class BrnUop(implicit p: ZnoParam) extends Bundle {
  val cond    = ZnoBranchCond()    // Condition
  val rid     = UInt(p.robwidth.W) // Reorder buffer index

  val ps1     = UInt(p.pwidth.W)   // Physical source #1
  val ps2     = UInt(p.pwidth.W)   // Physical source #2
  val ps_imm  = UInt(p.pwidth.W)   // Immediate (added to pc)
  val imm_inl = Bool()
  val imm_s   = Bool()
}

// Jump micro-op
class JmpUop(implicit p: ZnoParam) extends Bundle {
  val ind     = Bool()             // Indirect (use ps_base instead of pc)
  val rid     = UInt(p.robwidth.W) // Reorder buffer index
  val pd      = UInt(p.pwidth.W)   // Physical destination
  val ps_base = UInt(p.pwidth.W)   // Physical source (address base)
  val ps_imm  = UInt(p.pwidth.W)   // Offset (added to either ps_base or pc)
  val imm_inl = Bool()
  val imm_s   = Bool()
}

object UopMovCtl extends ChiselEnum {
  val NONE = Value
  val RS1  = Value // Move RS1 to RD
  val RS2  = Value // Move RS2 to RD
  val IMM  = Value // Move immediate to RD
  val PC   = Value // Move PC to RD
}

object UopImmCtl extends ChiselEnum {
  val NONE = Value // This micro-op has no associated immediate data
  val INL  = Value // This immediate is inlined into a physical register name
  val ALC  = Value // A physical register is allocated for the immediate
  val ZERO = Value // This immediate is zero
}

// Front-end (decode/rename) representation of a micro-op.
//
// The dispatch stage is responsible for transforming these signals into the
// more compact "back-end" representation of a particular kind of micro-op
// (see [IntUop], [LdUop], [StUop], [BrnUop], and [JmpUop] above) which
// will live in the appropriate scheduler/execution pipeline.
//
class Uop(implicit p: ZnoParam) extends Bundle {
  val kind    = UopKind()           // Micro-op type
  val enc     = RvEncType()         // Instruction encoding type
  val cond    = ZnoBranchCond()     // Branch condition
  val aluop   = ZnoAluOpcode()      // Arithmetic/logic unit operation
  val memw    = ZnoLdstWidth()      // Memory access width
  val rr      = Bool()              // Register result control
  val jmp_ind = Bool()              // Indirect jump
  val ld_sext = Bool()              // Sign-extend load result
  val movctl  = UopMovCtl()         // Move control
  //val immdata = RvImmData()         // Immediate data

  val rd      = UInt(p.awidth.W)    // Architectural destination
  val rs1     = UInt(p.awidth.W)    // Architectural source #1
  val rs2     = UInt(p.awidth.W)    // Architectural source #2

  val rid     = UInt(p.robwidth.W)  // Reorder buffer index
  val pd      = UInt(p.pwidth.W)    // Physical destination
  val ps1     = UInt(p.pwidth.W)    // Physical source #1
  val ps2     = UInt(p.pwidth.W)    // Physical source #2
  //val ps3     = UInt(p.pwidth.W)    // Physical source #3

  def drive_defaults(): Unit = {
    this.kind    := UopKind.U_ILL
    this.aluop   := ZnoAluOpcode.A_NOP
    this.cond    := ZnoBranchCond.B_NOP
    this.memw    := ZnoLdstWidth.W_NOP
    this.movctl  := UopMovCtl.NONE
    this.rr      := false.B
    this.jmp_ind := false.B
    this.ld_sext := false.B

    this.enc     := RvEncType.ENC_ILL
    this.rid     := 0.U
    this.rd      := 0.U
    this.rs1     := 0.U
    this.rs2     := 0.U
    this.pd      := 0.U
    this.ps1     := 0.U
    this.ps2     := 0.U
    //this.ps3     := 0.U
  }
}


// ----------------------------------------------------------------------------
// Dispatch / in-order resources

// Reorder buffer entry.
class ROBEntry(implicit p: ZnoParam) extends Bundle {
  val kind = UopKind()
  val rd   = UInt(p.awidth.W)
  val pd   = UInt(p.pwidth.W)
  val done = Bool()
}
object ROBEntry {
  // Creates an *empty* reorder buffer entry.
  def apply(implicit p: ZnoParam): ROBEntry = {
    (new ROBEntry).Lit(
      _.kind -> UopKind.U_INT,
      _.rd   -> 0.U,
      _.pd   -> 0.U,
      _.done -> false.B
    )
  }

  def apply(implicit p: ZnoParam, 
    kind: UopKind.Type, rd: UInt, pd: UInt, done: Bool
  ): ROBEntry = {
    var res = (new ROBEntry) 
    res.kind := kind
    res.rd   := rd
    res.pd   := pd
    res.done := done
    res
  }
}


