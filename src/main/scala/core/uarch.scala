package zno.core.uarch

import scala.collection.immutable.{ListMap, SeqMap}
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.OpaqueType

import zno.riscv.isa._

// Generic helper for defining common properties of sized/indexible objects.
class SizeDecl(sz: Int) {
  // The capacity of this structure (number of entries)
  val size: Int = sz
  // The width (in bits) of an index into this structure
  val idxWidth: Int = log2Ceil(size)
  // The [UInt] type representing an index into this structure
  def idx(): UInt = UInt(this.idxWidth.W)
}

// Helper for computing fetch block geometry
class FetchBlockParams(bytes: Int, xlen: Int) {
  require(isPow2(bytes), "Fetch block size must be a power of 2")

  val numBytes: Int = bytes
  val numWords: Int = (this.numBytes >> 2)

  val wordIdxWidth: Int = log2Ceil(numWords)
  val byteIdxWidth: Int = log2Ceil(numBytes)

  // Type of an index into a fetch block (by word)
  def wordIdx(): UInt = UInt(this.wordIdxWidth.W)
  // Type of an index into a fetch block (by byte)
  def byteIdx(): UInt = UInt(this.byteIdxWidth.W)

  // Type of the fetch block contents (in words)
  def dataWords(): Vec[UInt] = Vec(this.numWords, UInt(xlen.W))
  // Type of the fetch block contents (in bytes)
  def dataBytes(): Vec[UInt] = Vec(this.numBytes, UInt(8.W))
}

// Container for register file parameters
class RfParams(sz: Int,
  num_rp: Int = 1,
  num_wp: Int = 1,
){
  val size: Int = sz
  val idxWidth: Int = log2Ceil(size)
  def idx(): UInt = UInt(this.idxWidth.W)
  val numReadPorts: Int  = num_rp
  val numWritePorts: Int = num_wp
}

// ----------------------------------------------------------------------------
// Core configuration
// Variables used to parameterize different aspects of the ZNO core. 
//
// The current strategy is: when writing bundles/modules, pass [ZnoParam] 
// around as an implicit parameter, ie.
//
//  class MyModule(implicit p: ZnoParam) extends Module { 
//    val foo = Wire(p.xlen.W)
//    ... 
//  }
//

case class ZnoParam() {
  // General-purpose register width
  val xlen: Int = 32

  val fblk = new FetchBlockParams(32, 32) // Fetch block geometry
  val dec_win = new SizeDecl(8)       // Decode bandwidth
  val int_disp_win = new SizeDecl(6)  // Integer dispatch bandwidth

  val ras = new SizeDecl(32)  // Return Address Stack

  val ftq = new SizeDecl(8)   // Fetch Target Queue
  val fbq = new SizeDecl(16)  // Fetch Block Queue
  val dbq = new SizeDecl(16)  // Decode Block Queue
  val cfm = new SizeDecl(256) // Control-flow Map
  val rob = new SizeDecl(128) // Reorder Buffer

  // Architectural Register File
  val arf = new SizeDecl(32)
  // Physical Register File
  val prf = new RfParams(256,
    num_rp = 2,
    num_wp = 1,
  ) 

  // These are shortcuts for constructing certain types
  object Arn { def apply(): UInt = arf.idx() }
  object Prn { def apply(): UInt = prf.idx() }
  object PhysicalAddr { def apply(): UInt = UInt(xlen.W) }
  object VirtualAddr { def apply(): UInt = UInt(xlen.W) }

  object ProgramCounter { 
    def apply(): UInt = UInt(xlen.W) 
  }

  object FetchBlockAddr {
    def apply(): UInt = UInt((xlen - fblk.byteIdxWidth).W)
    def fromProgramCounter(pc: UInt): UInt = {
      pc(xlen-1, fblk.byteIdxWidth-1)
    }
  }


  // Sanity checks for parameters
  require(dec_win.size == fblk.numWords,
    "Decode width doesn't match the number of words in a fetch block")
}

// ----------------------------------------------------------------------------
// Opaque types
//
// The idea here is that we can more-strictly constrain what *Scala* operations
// are allowed on particular datatypes.
//
// Note that we're still defining a public '_underlying()' that exposes
// the actual underlying datatype, but this is really only supposed to be
// used to implement conversions between these types.
//
// NOTE: This is kind of a PITA because these don't work transparently with
// chiseltest. 

//class ProgramCounter(implicit p: ZnoParam) 
//  extends Record with OpaqueType 
//{
//  private val underlying = UInt(p.xlen.W)
//  val elements = SeqMap("" -> underlying)
//  def _underlying(): UInt = { this.underlying }
//
//  /// Return the next sequential value
//  def inc(): ProgramCounter = {
//    val _w = Wire(new ProgramCounter)
//    _w.underlying := this.underlying + 4.U
//    _w
//  }
//
//  /// Support addition with UInt
//  def +(that: UInt): ProgramCounter = {
//    val _w = Wire(new ProgramCounter)
//    _w.underlying := this.underlying + that
//    _w
//  }
//
//  /// Convert this value to the appropriate [FetchBlockAddr]
//  def toFetchBlockAddr(): FetchBlockAddr = {
//    val _w = Wire(new FetchBlockAddr)
//    _w._underlying() := this.underlying(p.xlen-1, p.fblk.byteIdxWidth-1)
//    _w
//  }
//
//  def fromConstantUInt(value: BigInt): ProgramCounter = {
//    val _w = Wire(new ProgramCounter)
//    _w.underlying := value.U(p.xlen.W)
//    _w
//  }
//}
//
//class FetchBlockAddr(implicit p: ZnoParam) 
//  extends Record with OpaqueType 
//{
//  val underlying_width: Int = p.xlen - p.fblk.byteIdxWidth
//  private val underlying = UInt(this.underlying_width.W)
//  val elements = SeqMap("" -> underlying)
//  def _underlying(): UInt = { this.underlying }
//
//  // Return the next-sequential fetch block address
//  def next(): FetchBlockAddr = {
//    val _w = Wire(new FetchBlockAddr)
//    _w.underlying := this.underlying + 1.U
//    _w
//  }
//
//  // Return a program counter value for this fetch block address
//  def toProgramCounter(): ProgramCounter = {
//    val _w = Wire(new ProgramCounter)
//    _w._underlying() := Cat(this.underlying, 0.U(p.fblk.byteIdxWidth.W))
//    _w
//  }
//
//  def toVirtualAddress(): VirtualAddress = {
//    val _w = Wire(new VirtualAddress)
//    _w._underlying() := Cat(this.underlying, 0.U(p.fblk.byteIdxWidth.W))
//    _w
//  }
//
//}
//
//class PhysicalAddress(implicit p: ZnoParam) 
//  extends Record with OpaqueType 
//{
//  private val underlying = UInt(p.xlen.W)
//  val elements = SeqMap("" -> underlying)
//  def _underlying(): UInt = { this.underlying }
//}
//
//class VirtualAddress(implicit p: ZnoParam) 
//  extends Record with OpaqueType 
//{
//  private val underlying = UInt(p.xlen.W)
//  val elements = SeqMap("" -> underlying)
//  def _underlying(): UInt = { this.underlying }
//}

// ----------------------------------------------------------------------------
// Common interfaces for bundles

// Implemented for bundles that contain a set of macro-ops
trait HasMacroOps {
  // Get a macro-op by index
  def get_mop(idx: UInt): MacroOp
}



// ----------------------------------------------------------------------------
// Control-flow/branch prediction definitions


/// Different types of architectural control-flow events
object ArchCfEventKind extends ChiselEnum {
  // Reset vector
  val RESET  = Value
  // A branch instruction retired in the backend
  val RETIRED_BRANCH = Value
}
class ArchitecturalCfEvent(implicit p: ZnoParam) extends Bundle {
  val kind = ArchCfEventKind()
  val npc  = p.ProgramCounter()
}
/// Different types of speculative control-flow events
object SpecCfEventKind extends ChiselEnum {
  val PREDICT = Value
}
class SpeculativeCfEvent(implicit p: ZnoParam) extends Bundle {
  val kind = SpecCfEventKind()
  val npc  = p.ProgramCounter()
}

object CfEventKind extends ChiselEnum {
  val RESET = Value
  val RETIRED_BRANCH = Value
  val PREDICTED_BRANCH = Value
}
class ControlFlowEvent(implicit p: ZnoParam) extends Bundle {
  val kind = CfEventKind()
  val npc  = p.ProgramCounter()
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
  val data = p.fblk.dataWords()
  // Default values
  def drive_defaults(): Unit = {
    this.addr := 0.U
    this.data := 0.U.asTypeOf(p.fblk.dataWords())
  }
}

// ----------------------------------------------------------------------------
// Instruction Predecode definitions

// Different ways of computing a branch target address
object BranchAddressingType extends ChiselEnum {
  val BA_NONE = Value
  val BA_REL  = Value // "PC-relative"
  val BA_DIR  = Value // "Direct" (ie. x0 + immediate)
  val BA_IND  = Value // "Indirect" (ie. rs1 + immediate)
}

// Different types of [abstract] branch operations.
object BranchType extends ChiselEnum {
  val BT_NONE     = Value
  val BT_BRN      = Value
  val BT_JAL      = Value
  val BT_JALR     = Value
  val BT_RET      = Value
  val BT_CALL     = Value
}

class BranchInfo(implicit p: ZnoParam) extends Bundle {
  val btype    = BranchType()
  val batype   = BranchAddressingType()
}

class PredecodeBlock(implicit p: ZnoParam) extends Bundle {
  val fblk = new FetchBlock
  val pdmops = Vec(p.dec_win.size, new PdMop)
}

// A "predecoded" macro-op
class PdMop(implicit p: ZnoParam) extends Bundle {
  val ill      = Bool()
  val binfo    = new BranchInfo
  val imm_data = new RvImmData
  val imm_ctl  = new ImmCtl
}


// ----------------------------------------------------------------------------
// Instruction decode definitions

// A block of decoded micro-ops (produced by the decode unit).
class DecodeBlock(implicit p: ZnoParam) extends Bundle 
  with HasMacroOps
{
  val addr = p.FetchBlockAddr()
  val cfm_idx = p.cfm.idx()
  val data = Vec(p.dec_win.size, new MacroOp)
  def size(): Int = p.dec_win.size

  override def get_mop(idx: UInt): MacroOp = {
    require(idx.getWidth == p.dec_win.idxWidth)
    this.data(idx)
  }
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
  val U_CSR = Value
}

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
  val ALC  = Value // Storage must be allocated for this immediate
}

// Bits determining how an immediate value is recovered in the backend.
class ImmCtl(implicit p: ZnoParam) extends Bundle {
  val ifmt    = RvImmFmt()       // RISC-V immediate format
  val storage = ImmStorageKind() // How is this value stored?
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

  // Immediate control bits
  val imm_ctl = new ImmCtl

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


