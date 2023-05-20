package zno.core.uarch

import scala.collection.immutable.{ListMap, SeqMap}
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.OpaqueType

import zno.riscv.isa._

// Generic helper for defining common properties of sized/indexible objects.
class SizeDecl(s: Int) {
  // The capacity of this structure (number of entries)
  val size: Int = s
  // The width (in bits) of an index into this structure
  val idxWidth: Int = log2Ceil(size)
  // The [UInt] type representing an index into this structure
  def idx(): UInt = UInt(this.idxWidth.W)
}

// Helper for computing fetch block geometry
class FetchBlockParams(bytes: Int) {
  require(isPow2(bytes), "Fetch block size must be a power of 2")

  val numBytes: Int = bytes
  val byteIdxWidth: Int = log2Ceil(numBytes)
  def byteIdx(): UInt = UInt(this.byteIdxWidth.W)

  val numWords: Int = (this.numBytes >> 2)
  val wordIdxWidth: Int = log2Ceil(numWords)
  def wordIdx(): UInt = UInt(this.wordIdxWidth.W)
}


// ----------------------------------------------------------------------------
// Core configuration
// Variables used to parameterize different aspects of the ZNO core. 

case class ZnoParam() {
  // General-purpose register width
  val xlen: Int = 32

  val fblk = new FetchBlockParams(32) // Fetch block geometry
  val dec_win = new SizeDecl(8)       // Decode bandwidth
  val int_disp_win = new SizeDecl(6)  // Integer dispatch bandwidth

  val ftq = new SizeDecl(16)  // Fetch Target Queue
  val fbq = new SizeDecl(16)  // Fetch Block Queue
  val dbq = new SizeDecl(16)  // Decode Block Queue
  val cfm = new SizeDecl(256) // Control-flow Map
  val rob = new SizeDecl(128) // Reorder Buffer
  val arf = new SizeDecl(32)  // Architectural Register File
  val prf = new SizeDecl(256) // Physical Register File

  // These are shortcuts for constructing certain types
  object Arn { def apply(): UInt = arf.idx() }
  object Prn { def apply(): UInt = prf.idx() }
  object FetchBlockData { 
    def apply(): Vec[UInt] = Vec(fblk.numWords, UInt(xlen.W)) 
  }

  // Sanity checks for parameters
  require(dec_win.size == fblk.numWords,
    "Decode width doesn't match the number of words in a fetch block")
}

// ----------------------------------------------------------------------------
// Opaque types
//
// The idea here is that we can more-strictly constrain what operations are 
// allowed on particular datatypes.
//
// Note that we're still defining a public '_underlying()' that exposes
// the actual underlying datatype, but this is really only supposed to be
// used to implement conversions between these types.

class ProgramCounter(implicit p: ZnoParam) 
  extends Record with OpaqueType 
{
  private val underlying = UInt(p.xlen.W)
  val elements = SeqMap("" -> underlying)
  def _underlying(): UInt = { this.underlying }

  /// Return the next sequential value
  def inc(): ProgramCounter = {
    val _w = Wire(new ProgramCounter)
    _w.underlying := this.underlying + 4.U
    _w
  }

  /// Support addition with UInt
  def +(that: UInt): ProgramCounter = {
    val _w = Wire(new ProgramCounter)
    _w.underlying := this.underlying + that
    _w
  }

  /// Convert this value to the appropriate [FetchBlockAddr]
  def toFetchBlockAddr(): FetchBlockAddr = {
    val _w = Wire(new FetchBlockAddr)
    _w._underlying() := this.underlying(p.xlen-1, p.fblk_width-1)
    _w
  }
}

class FetchBlockAddr(implicit p: ZnoParam) 
  extends Record with OpaqueType 
{
  val underlying_width: Int = p.xlen - p.fblk_width
  private val underlying = UInt(this.underlying_width.W)
  val elements = SeqMap("" -> underlying)
  def _underlying(): UInt = { this.underlying }

  // Return the next-sequential fetch block address
  def next(): FetchBlockAddr = {
    val _w = Wire(new FetchBlockAddr)
    _w.underlying := this.underlying + 1.U
    _w
  }

  // Return the full 
  def toProgramCounter(): ProgramCounter = {
    val _w = Wire(new ProgramCounter)
    _w._underlying() := Cat(this.underlying, 0.U(p.fblk_width.W))
    _w
  }
}

class PhysicalAddress(implicit p: ZnoParam) 
  extends Record with OpaqueType 
{
  private val underlying = UInt(p.xlen.W)
  val elements = SeqMap("" -> underlying)
  def _underlying(): UInt = { this.underlying }
}

class VirtualAddress(implicit p: ZnoParam) 
  extends Record with OpaqueType 
{
  private val underlying = UInt(p.xlen.W)
  val elements = SeqMap("" -> underlying)
  def _underlying(): UInt = { this.underlying }
}



// ----------------------------------------------------------------------------
// Control-flow/branch prediction definitions

/// Different types of architectural control-flow events
object ArchCfEventKind extends ChiselEnum {
  val RESET  = Value
  val RETIRED_BRANCH = Value
}

/// Different types of speculative control-flow events
object SpecCfEventKind extends ChiselEnum {
  val PREDICT = Value
}

class ArchitecturalCfEvent(implicit p: ZnoParam) extends Bundle {
  val kind = ArchCfEventKind()
  val npc  = new ProgramCounter
}

class SpeculativeCfEvent(implicit p: ZnoParam) extends Bundle {
  val kind = SpecCfEventKind()
  val npc  = new ProgramCounter
}



// ----------------------------------------------------------------------------
// Instruction fetch definitions

// Connection between the frontcore and instruction memories
class ZnoInstBusIO(implicit p: ZnoParam) extends Bundle {
  val req  = Decoupled(new FetchBlockAddr)
  val resp = Flipped(Decoupled(new FetchBlock))
}

// A block of fetched bytes. 
class FetchBlock(implicit p: ZnoParam) extends Bundle {
  // The address of this block
  val addr = new FetchBlockAddr
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

class PredecodeBlock(implicit p: ZnoParam) extends Bundle {
  val addr = new FetchBlockAddr
  val data = Vec(p.dec_win.size, new PdMop)
}

// A block of decoded micro-ops.
class DecodeBlock(implicit p: ZnoParam) extends Bundle {
  val addr = new FetchBlockAddr
  val data = Vec(p.dec_win.size, new MacroOp)
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


