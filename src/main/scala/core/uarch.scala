package zno.core.uarch

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import zno.riscv.isa._

// ----------------------------------------------------------------------------
// Core configuration

// Variables used to parameterize different aspects of the ZNO core. 
//
// FIXME: There are probably additional constraints you can impose on the 
// possible sizes of different structues here. 
//
case class ZnoParam(
  prf_sz:   Int = 64, // Physical Register File size
  rob_sz:   Int = 128,// Reorder Buffer size
  sch_sz:   Int = 8,  // Scheduler Queue size
  fbq_sz:   Int = 4,  // Fetch Block Queue size
  dbq_sz:   Int = 4,  // Decode Block Queue size
  ftq_sz:   Int = 4,  // Fetch Target Queue size
  cfm_sz:   Int = 8,  // Control-flow Map size
  opq_sz:   Int = 32, // [Macro-]Op Queue size
) {

  // General-purpose register width
  val xlen:     Int = 32
  // Architectural Register File size
  val num_areg: Int = 32
  // Architectural register index width
  val awidth:   Int = log2Ceil(num_areg)

  // Physical register index width
  val pwidth:   Int = log2Ceil(prf_sz)
  // Reorder buffer index width
  val robwidth: Int = log2Ceil(rob_sz)
  // Decode width: the number of macro-ops decoded in parallel
  val dec_bw:   Int = 8

  val int_dis_width: Int = 6

  // Fetch block geometry
  val fblk_bytes: Int = 32 
  val fblk_width: Int = log2Ceil(fblk_bytes)
  val fblk_words: Int = fblk_bytes >> 2
  val fblk_idx_width: Int = log2Ceil(cfm_sz)
  val fblk_addr_width: Int = xlen - fblk_width
  val fblk_word_idx_width: Int = log2Ceil(fblk_words)
  object FetchBlockData { 
    def apply(): Vec[UInt] = Vec(fblk_words, UInt(xlen.W)) 
  }
  object FetchBlockAddr {
    def apply(): UInt = UInt(fblk_addr_width.W)
    def from_pc(pc: UInt): UInt = {
      require(pc.getWidth == xlen)
      pc(fblk_addr_width-1, 0)
    }
  }
  require(isPow2(fblk_bytes), "Fetch block size must be a power of 2")
  require(dec_bw == fblk_words)


  // Architectural register name
  object Arn    { def apply(): UInt = UInt(awidth.W) }
  // Physical register name
  object Prn    { def apply(): UInt = UInt(pwidth.W) }
  // Reorder buffer index
  object RobIdx { def apply(): UInt = UInt(robwidth.W) }
  // A program counter value
  object ProgramCounter { def apply(): UInt = UInt(xlen.W) }

  // Index of a macro-op within a decode window
  object MopIdx { def apply(): UInt = UInt(log2Ceil(dec_bw).W) }
}

// ----------------------------------------------------------------------------
// Instruction fetch definitions

// Connection between the frontcore and instruction memories
class ZnoInstBusIO(implicit p: ZnoParam) extends Bundle {
  val req  = Decoupled(p.FetchBlockAddr())
  val resp = Flipped(Decoupled(new FetchBlock))
}

// A block of fetched bytes. 
class FetchBlock(implicit p: ZnoParam) extends Bundle {
  // The address of this block
  val addr = p.FetchBlockAddr()
  // Fetched bytes in this block
  val data = p.FetchBlockData()
  // Default values
  def drive_defaults(): Unit = {
    this.addr := 0.U
    this.data := 0.U.asTypeOf(p.FetchBlockData())
  }
}

// ----------------------------------------------------------------------------
// Instruction decode definitions

// A block of decoded micro-ops.
class DecodeBlock(implicit p: ZnoParam) extends Bundle {
  val addr = p.FetchBlockAddr()
  val data = Vec(p.dec_bw, new MacroOp)
}

// Describing the different kinds of instruction operands. 
object SrcType extends ChiselEnum {
  val S_NONE = Value
  val S_ZERO = Value
  val S_REG  = Value
  val S_IMM  = Value
  val S_PC   = Value
}

// Describing different types of integer operations
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

// Describing different branch conditions
object BrnOp extends ChiselEnum {
  val B_NOP = Value
  val B_EQ  = Value
  val B_NE  = Value
  val B_LT  = Value
  val B_GE  = Value
  val B_LTU = Value
  val B_GEU = Value
}

// Different types of [abstract] branch operations.
object BranchType extends ChiselEnum {
  val BT_NONE = Value
  val BT_CALL = Value
  val BT_RET  = Value
  val BT_JAL  = Value
  val BT_JALR = Value
  val BT_BRN  = Value
}

// Different types of RISC-V branch instruction encodings. 
object BranchInstKind extends ChiselEnum {
  val PB_NONE = Value
  val PB_JAL  = Value
  val PB_JALR = Value
  val PB_BRN  = Value
}

// Describing different load/store widths
object LdStWidth extends ChiselEnum {
  val W_NOP = Value
  val W_B   = Value
  val W_H   = Value
  val W_W   = Value
}

// Different fundamental types of micro-ops in the machine. 
object UopKind extends ChiselEnum {
  val U_ILL = Value
  val U_INT = Value
  val U_LD  = Value
  val U_ST  = Value
  val U_BRN = Value
  val U_JMP = Value
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

// Indicates when an integer operation can be squashed into a mov/zero idiom.
object MovCtl extends ChiselEnum {
  val NONE = Value
  val SRC1 = Value // Move src1 into rd
  val SRC2 = Value // Move src2 into rd
  val ZERO = Value // Move zero into rd
}

// Bits describing how an immediate should be stored.
object ImmStorageKind extends ChiselEnum {
  val NONE = Value // No immediate data
  val ZERO = Value // This immediate is zero
  val INL  = Value // Can be inlined into a physical register index
  val ALC  = Value // Must be allocated a physical register
}

// Bits determining how an immediate value is recovered in the backend.
class ImmCtl(implicit p: ZnoParam) extends Bundle {
  val ifmt    = RvImmFmt()       // RISC-V immediate format
  val storage = ImmStorageKind() // How is this value stored?
}

class PdMop(implicit p: ZnoParam) extends Bundle {
  val opcd    = UInt(p.xlen.W)
  val btype   = BranchType()
  val ifmt    = RvImmFmt()
  val imm     = UInt(p.xlen.W)
}

class MacroOp(implicit p: ZnoParam) extends Bundle {
  val kind    = UopKind()
  val alu_op  = AluOp()
  val brn_op  = BrnOp()
  val rr      = Bool()
  val mem_w   = LdStWidth()
  val ld_sext = Bool()

  // Identifiers for the type of source operands. 
  // During rename, we expect these might be recomputed. 
  val src1    = SrcType()
  val src2    = SrcType()

  // Architectural registers
  val rd      = p.Arn()
  val rs1     = p.Arn()
  val rs2     = p.Arn()

  // Whether or not this instruction has been squashed into a 'move'.
  // We expect this might be recomputed during rename.
  val mov_ctl = MovCtl()

  // Immediate control and [unexpanded] data
  val imm_ctl = new ImmCtl
  val imm_data = new RvImmData

  // "Does this macro op allocate a new physical destination register?"
  def is_allocation(): Bool = {
    this.is_scheduled() && this.rr
  }

  // "Is this macro op actually schedulable?"
  def is_scheduled(): Bool = {
    this.mov_ctl === MovCtl.NONE
  }

  // "Is this an integer op?"
  def is_int(): Bool = (this.kind === UopKind.U_INT)
  // "Is this a load?"
  def is_ld(): Bool = (this.kind === UopKind.U_LD)
  // "Is this a store?"
  def is_st(): Bool = (this.kind === UopKind.U_ST)
}


// ----------------------------------------------------------------------------
// Integer pipeline definitions

// Integer micro-op
class IntUop(implicit p: ZnoParam) extends Bundle {
  val ridx    = p.RobIdx() // Reorder buffer index
  val alu_op  = AluOp()    // Opcode
  val pd      = p.Prn()    // Physical destination
  val ps1     = p.Prn()    // Physical source #1
  val ps2     = p.Prn()    // Physical source #2
  val src1    = SrcType()
  val src2    = SrcType()
  val imm_ctl = new ImmCtl // Immediate control
}

// Branch micro-op
class BrnUop(implicit p: ZnoParam) extends Bundle {
  val cond    = BrnOp()    // Condition
  val ps1     = p.Prn()    // Physical source #1
  val ps2     = p.Prn()    // Physical source #2
  val ps_imm  = p.Prn()    // Immediate (added to pc)
  val imm_ctl = new ImmCtl // Immediate control
}

// Jump micro-op
class JmpUop(implicit p: ZnoParam) extends Bundle {
  val ind     = Bool()     // Indirect (use ps_base instead of pc)
  val pd      = p.Prn()    // Physical destination
  val ps_base = p.Prn()    // Physical source (address base)
  val ps_imm  = p.Prn()    // Offset (added to either ps_base or pc)
  val imm_ctl = new ImmCtl // Immediate control
}

// ----------------------------------------------------------------------------
// Load/store pipeline definitions

// Load micro-op
class LdUop(implicit p: ZnoParam) extends Bundle {
  val accw    = LdStWidth() // Memory access width
  val sext    = Bool()      // 32-bit sign-extended result
  val pd      = p.Prn()     // Physical destination
  val ps_base = p.Prn()     // Physical source (address base)
  val ps_imm  = p.Prn()     // Immediate (added to ps_base)
  val imm_ctl = new ImmCtl  // Immediate control
}

// Store micro-op
class StUop(implicit p: ZnoParam) extends Bundle {
  val accw    = LdStWidth() // Memory access width
  val ps_data = p.Prn()     // Physical source (data)
  val ps_base = p.Prn()     // Physical source (address base)
  val ps_imm  = p.Prn()     // Immediate (added to ps_base)
  val imm_ctl = new ImmCtl  // Immediate control
}


