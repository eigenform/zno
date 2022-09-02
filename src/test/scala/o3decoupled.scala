import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.experimental._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._
import org.scalatest.flatspec.AnyFlatSpec

// ============================================================================
// Datatypes and bundle declarations

object InstType extends ChiselEnum { val LIT, ADD, NOP, NONE = Value }
class Instruction(implicit p: Param) extends Bundle {
  val op  = InstType()        // The type of instruction
  val rd  = UInt(p.awidth.W)  // Result register index
  val rs1 = UInt(p.awidth.W)  // Source register #1 index
  val rs2 = UInt(p.awidth.W)  // Source register #2 index
  val imm = UInt(32.W)        // Immediate data
  val imm_en = Bool()
  def drive_defaults(): Unit = {
    this.op     := InstType.NONE
    this.rd     := 0.U
    this.rs1    := 0.U
    this.rs2    := 0.U
    this.imm    := 0.U
    this.imm_en := false.B
  }
}

object Instr {
  implicit val p = Param()
  def apply(op: InstType.Type, rd: Int, rs1: Int, rs2: Int, 
    imm: Option[BigInt]): Instruction = 
  {
    val imm_en = imm match {
      case Some(x) => true.B
      case None    => false.B
    }
    val imm_val = imm match {
      case Some(x) => x.U
      case None    => 0.U
    }
    (new Instruction).Lit(
        _.op     -> op,
        _.rd     -> rd.U,
        _.rs1    -> rs1.U,
        _.rs2    -> rs2.U,
        _.imm    -> imm_val,
        _.imm_en -> imm_en,
    )
  }
  def apply(): Instruction = {
    (new Instruction).Lit(
      _.op     -> InstType.NONE,
      _.rd     -> 0.U,
      _.rs1    -> 0.U,
      _.rs2    -> 0.U,
      _.imm    -> 0xdeadbeefL.U,
      _.imm_en -> false.B,
    )
  }

}

class RFReadPort(implicit p: Param) extends Bundle {
  val addr = Input(UInt(p.awidth.W))
  val data = Output(UInt(32.W))
  def drive_defaults(): Unit = {
    this.addr := 0.U
  }
}

class RFWritePort(implicit p: Param) extends Bundle {
  val addr = Input(UInt(p.awidth.W))
  val data = Input(UInt(32.W))
  val en   = Input(Bool())
  def drive_defaults(): Unit = {
    this.addr := 0.U
    this.data := 0.U
    this.en   := false.B
  }
}

class MicroOp(implicit p: Param) extends Bundle {
  val op   = InstType()
  val pd   = UInt(p.awidth.W)
  val ps1  = UInt(p.awidth.W)
  val ps2  = UInt(p.awidth.W)
  val ridx = UInt(p.robwidth.W)
  val imm  = UInt(32.W)
  def drive_defaults(): Unit = {
    this.op   := InstType.NONE
    this.pd   := 0.U
    this.ps1  := 0.U
    this.ps2  := 0.U
    this.imm  := 0.U
    this.ridx := 0.U
  }
}

class DataPacket(implicit p: Param) extends Bundle {
  val pd   = UInt(p.pwidth.W)
  val x    = UInt(32.W)
  val y    = UInt(32.W)
  val ridx = UInt(p.robwidth.W)
  def drive_defaults(): Unit = {
    this.pd   := 0.U
    this.x    := 0.U
    this.y    := 0.U
    this.ridx := 0.U
  }
}

class Result(implicit p: Param) extends Bundle {
  val pd   = UInt(p.pwidth.W)
  val ridx = UInt(p.robwidth.W)
  val data = UInt(32.W)
  def drive_defaults(): Unit = {
    this.pd   := 0.U
    this.data := 0.U
    this.ridx := 0.U
  }
}

class MapReadPort(implicit p: Param) extends Bundle {
  val areg = Input(UInt(p.awidth.W))
  val preg = Output(UInt(p.pwidth.W))
  def drive_defaults(): Unit = {
    this.areg := 0.U
  }
}

class MapWritePort(implicit p: Param) extends Bundle {
  val areg = Input(UInt(p.awidth.W))
  val preg = Input(UInt(p.pwidth.W))
  val en   = Input(Bool())
  def drive_defaults(): Unit = {
    this.areg := 0.U
    this.preg := 0.U
    this.en   := false.B
  }
}

class MapAllocPort(implicit p: Param) extends Bundle {
  val areg = Input(UInt(p.awidth.W))
  val en   = Input(Bool())
  val preg = Valid(UInt(p.pwidth.W))
  def drive_defaults(): Unit = {
    this.areg := 0.U
    this.en   := false.B
  }
}

class MapFreePort(implicit p: Param) extends Bundle {
  val preg = Input(UInt(p.pwidth.W))
  val en   = Input(Bool())
  def drive_defaults(): Unit = {
    this.preg := 0.U
    this.en   := false.B
  }
}

class ROBEntry(implicit p: Param) extends Bundle {
  val rd   = UInt(p.awidth.W)
  val pd   = UInt(p.pwidth.W)
  val done = Bool()
}
object ROBEntry {
  def apply(implicit p: Param): ROBEntry = {
    (new ROBEntry).Lit(
      _.rd   -> 0.U,
      _.pd   -> 0.U,
      _.done -> false.B
    )
  }
}

class ROBAllocPort(implicit p: Param) extends Bundle {
  val rd  = Input(UInt(p.awidth.W))
  val pd  = Input(UInt(p.pwidth.W))
  val en  = Input(Bool())
  val idx = Output(Valid(UInt(p.robwidth.W)))
  def drive_defaults(): Unit = {
    this.rd := 0.U
    this.pd := 0.U
    this.en := false.B
  }
}
class ROBWritePort(implicit p: Param) extends Bundle {
  val idx = Input(UInt(p.robwidth.W))
  val en  = Input(Bool())
  def drive_defaults(): Unit = {
    this.idx := 0.U
    this.en  := false.B
  }
}

class DecodePacket(implicit p: Param) extends Bundle {
  val inst = new Instruction
  val pc   = UInt(32.W)
}

// ============================================================================
// Modules


// ============================================================================
// Top module


case class Param(
  num_areg: Int = 32,
  num_preg: Int = 64,
  rob_sz:   Int = 32,
) {
  val robwidth: Int = log2Ceil(rob_sz)
  val awidth:   Int = log2Ceil(num_areg)
  val pwidth:   Int = log2Ceil(num_preg)
}

class PipelineBackend extends Module {
  implicit val p = Param()

  // A decoded instruction and its associated program counter
  val req = IO(Flipped(Decoupled(new DecodePacket)))

  // Indicates when the backend is ready to accept a decoded instruction
  req.ready := true.B

}


// ============================================================================
// Testbench

class O3DecoupledFlowSpec extends AnyFlatSpec with ChiselScalatestTester {
  import InstType._
  implicit val p = Param()

  behavior of "PipelineBackend"
  it should "work" in {
    test(new PipelineBackend) { 
      dut => 
      implicit val clk: Clock = dut.clock
      val prog = Seq(
        Instr(LIT, 1, 0, 0, Some(0x1)),
        Instr(LIT, 2, 0, 0, Some(0x2)),
        Instr(ADD, 3, 1, 2, None),
        Instr(ADD, 4, 1, 3, None),
        Instr(ADD, 1, 2, 4, None),
        Instr(ADD, 1, 0, 1, None),
        Instr(NOP, 0, 0, 0, None),
        Instr(NOP, 0, 0, 0, None),
        Instr(NOP, 0, 0, 0, None),
        Instr(NOP, 0, 0, 0, None),
      )
      var pc  = 0
      var cyc = 0

      while (cyc < 8) {
        println("----------------------------------------")

        // Stall when the backend is not ready to receive
        // a newly-decoded instruction from us

        val backend_rdy = dut.req.ready.peek().litToBoolean
        if (backend_rdy) {
          dut.req.bits.poke((new DecodePacket).Lit(
            _.pc   -> (pc * 4).U,
            _.inst -> prog(pc),
          ))
          dut.req.valid.poke(true.B)
          pc += 1
        } 
        else {
          dut.req.valid.poke(false.B)
          dut.req.bits.poke((new DecodePacket).Lit(
            _.pc   -> 0xdeadbeefL.U,
            _.inst -> Instr(),
          ))
        }

        clk.step()
        cyc += 1
      }
    }
  }
}


