package zno.core.uarch

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._

import zno.riscv.isa._

// Variables used to parameterize different aspects of the ZNO core. 
case class ZnoParam(
  xlen:     Int = 32, // Register width (in bits)
  num_areg: Int = 32, // Number of architectural registers
  num_preg: Int = 64, // Number of physical registers
  rob_sz:   Int = 32, // Number of reorder buffer entries
  sch_sz:   Int = 8,  // Number of scheduler/reservation entries
  opq_sz:   Int = 32, // Number of buffered instrs between decode and dispatch
  id_width: Int = 4,  // Number of instructions in a decode packet
) {
  val line_bytes: Int = 32               // Number of bytes in an L1 cache line
  val line_bits:  Int = line_bytes * 8   // Number of bits in an L1 cache line
  val line_words: Int = line_bytes / 4   // Number of words in an L1 cache line

  val robwidth: Int = log2Ceil(rob_sz)   // Reorder buffer index width
  val awidth:   Int = log2Ceil(num_areg) // Architectural register index width
  val pwidth:   Int = log2Ceil(num_preg) // Physical register index width
}

// Indicates a type of execution unit used to perform an operation.
object ExecutionUnit extends ChiselEnum {
  val EU_ILL = Value // No execution unit (illegal instruction)
  val EU_NOP = Value // No execution unit (explicit no-op)
  val EU_ALU = Value // ALU instruction
  val EU_LSU = Value // Load/store instruction
  val EU_BCU = Value // Branch/jump/compare instruction
}

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

object ZnoLdstOpcode extends ChiselEnum {
  val M_NOP = Value
  val M_LB  = Value
  val M_LH  = Value
  val M_LW  = Value
  val M_LBU = Value
  val M_LHU = Value
  val M_SB  = Value
  val M_SH  = Value
  val M_SW  = Value
  val M_ILL = Value
}

object ZnoBranchOpcode extends ChiselEnum {
  val B_NOP = Value
  val B_BRN = Value
  val B_JMP = Value
}

object ZnoBranchCond extends ChiselEnum {
  val EQ  = Value
  val NEQ = Value
  val LT  = Value
  val LTU = Value
  val GE  = Value
  val GEU = Value
}

object ZnoLdstWidth extends ChiselEnum {
  val W_B = Value
  val W_H = Value
  val W_W = Value
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


// ----------------------------------------------------------------------------



class StUop(implicit p: ZnoParam) extends Bundle {
  val accw    = ZnoLdstWidth()     // Memory access width
  val rid     = UInt(p.robwidth.W) // Reorder buffer index
  val ps_data = UInt(p.pwidth.W)   // Physical source (data)
  val ps_base = UInt(p.pwidth.W)   // Physical source (address base)
  val imm     = UInt(p.xlen.W)     // Immediate (added to ps_base)
}

class LdUop(implicit p: ZnoParam) extends Bundle {
  val accw    = ZnoLdstWidth()     // Memory access width
  val rid     = UInt(p.robwidth.W) // Reorder buffer index
  val pd      = UInt(p.pwidth.W)   // Physical destination
  val ps_base = UInt(p.pwidth.W)   // Physical source (address base)
  val imm     = UInt(p.xlen.W)     // Immediate (added to ps_base)
}

class BrnUop(implicit p: ZnoParam) extends Bundle {
  val cond    = ZnoBranchCond()    // Condition
  val rid     = UInt(p.robwidth.W) // Reorder buffer index
  val ps1     = UInt(p.pwidth.W)   // Physical source #1
  val ps2     = UInt(p.pwidth.W)   // Physical source #2
  val imm     = UInt(p.xlen.W)     // Immediate (added to pc)
}

class JmpUop(implicit p: ZnoParam) extends Bundle {
  val rid     = UInt(p.robwidth.W) // Reorder buffer index
  val pd      = UInt(p.pwidth.W)   // Physical destination
  val indir   = Bool()             // Indirect (use ps_base instead of pc)
  val ps_base = UInt(p.pwidth.W)   // Physical source (address base)
  val imm     = UInt(p.xlen.W)     // Offset (added to either ps_base or pc)
}



class Uop(implicit p: ZnoParam) extends Bundle {
  val brnop = ZnoBranchOpcode()    // Branch operation
  val aluop = ZnoAluOpcode()       // Arithmetic/logic unit operation
  val memop = ZnoLdstOpcode()      // Memory operation

  val enc   = RvEncType()          // Instruction encoding type
  val rid   = UInt(p.robwidth.W)   // Reorder buffer index
  val rr    = Bool()               // Register result

  val imm_s   = Bool()             // Immediate sign/high bit
  val imm_inl = Bool()             // Immediate inlined into ps3

  val rd    = UInt(p.awidth.W)     // Architectural destination
  val rs1   = UInt(p.awidth.W)     // Architectural source #1
  val rs2   = UInt(p.awidth.W)     // Architectural source #2

  val pd    = UInt(p.pwidth.W)     // Physical destination
  val ps1   = UInt(p.pwidth.W)     // Physical source #1
  val ps2   = UInt(p.pwidth.W)     // Physical source #2
  val ps3   = UInt(p.pwidth.W)     // Physical source #3

  def drive_defaults(): Unit = {
      this.aluop   := ZnoAluOpcode.A_NOP
      this.brnop   := ZnoBranchOpcode.B_NOP
      this.memop   := ZnoLdstOpcode.M_NOP
      this.imm_s   := false.B
      this.imm_inl := false.B
      this.enc     := RvEncType.ENC_ILL
      this.rid     := 0.U
      this.rr      := false.B
      this.rd      := 0.U
      this.rs1     := 0.U
      this.rs2     := 0.U
      this.pd      := 0.U
      this.ps1     := 0.U
      this.ps2     := 0.U
      this.ps3     := 0.U
  }
}

class ZnoFetchPacket(implicit p: ZnoParam) extends Bundle {
  val addr = UInt(p.xlen.W)
  val data = Vec(p.line_words, UInt(p.xlen.W)) 
}


// Reorder buffer entry.
class ROBEntry(implicit p: ZnoParam) extends Bundle {
  val rd   = UInt(p.awidth.W)
  val pd   = UInt(p.pwidth.W)
  val done = Bool()
}
object ROBEntry {
  // Creates an *empty* reorder buffer entry.
  def apply(implicit p: ZnoParam): ROBEntry = {
    (new ROBEntry).Lit(
      _.rd   -> 0.U,
      _.pd   -> 0.U,
      _.done -> false.B
    )
  }

  def apply(implicit p: ZnoParam, rd: UInt, pd: UInt, done: Bool): ROBEntry = {
    var res = (new ROBEntry) 
    res.rd   := rd
    res.pd   := pd
    res.done := done
    res
  }
}


