import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.experimental._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._
import org.scalatest.flatspec.AnyFlatSpec

// FIXME: Move this into src/main/scala/riscv at some point
// NOTE: Try to make this legible in more ways than one.

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
}

object Instr {
  // FIXME: I don't know how to pass implicit parameters to an object.
  // This *does not* inherit the parameters from whatever context you're
  // calling this in. 
  implicit val p = Param()

  // Create a new instruction.
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

  // Create an *empty* instruction. 
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

// Register file read port.
class RFReadPort(implicit p: Param) extends Bundle {
  val addr = Input(UInt(p.awidth.W))
  val data = Output(UInt(32.W))
}

// Register file write port.
class RFWritePort(implicit p: Param) extends Bundle {
  val addr = Input(UInt(p.awidth.W))
  val data = Input(UInt(32.W))
  val en   = Input(Bool())
}

class MicroOp(implicit p: Param) extends Bundle {
  val op   = InstType()
  val pd   = UInt(p.awidth.W)
  val ps1  = UInt(p.awidth.W)
  val ps2  = UInt(p.awidth.W)
  val ridx = UInt(p.robwidth.W)
  val imm  = UInt(32.W)
}

class DataPacket(implicit p: Param) extends Bundle {
  val pd   = UInt(p.pwidth.W)
  val x    = UInt(32.W)
  val y    = UInt(32.W)
  val ridx = UInt(p.robwidth.W)
}

class Result(implicit p: Param) extends Bundle {
  val pd   = UInt(p.pwidth.W)
  val ridx = UInt(p.robwidth.W)
  val data = UInt(32.W)
}

// Reorder buffer entry.
class ROBEntry(implicit p: Param) extends Bundle {
  val rd   = UInt(p.awidth.W)
  val pd   = UInt(p.pwidth.W)
  val done = Bool()
}
object ROBEntry {
  // Creates an *empty* reorder buffer entry.
  def apply(implicit p: Param): ROBEntry = {
    (new ROBEntry).Lit(
      _.rd   -> 0.U,
      _.pd   -> 0.U,
      _.done -> false.B
    )
  }
}

// Reorder buffer allocation port.
class ROBAllocPort(implicit p: Param) extends Bundle {
  val rd  = Input(UInt(p.awidth.W))
  val pd  = Input(UInt(p.pwidth.W))
  val en  = Input(Bool())
  val idx = Output(Valid(UInt(p.robwidth.W)))
}

// Reorder buffer entry write port.
class ROBWritePort(implicit p: Param) extends Bundle {
  val idx = Input(UInt(p.robwidth.W))
  val en  = Input(Bool())
}

// A packet containing instructions from the front-end of the pipeline.
class DecodePacket(implicit p: Param) extends Bundle {
  val pc   = UInt(32.W)
  val inst = Vec(p.dec_width, new Instruction)
}

// Register map read port.
class MapReadPort(implicit p: Param) extends Bundle {
  val rs1 = Input(UInt(p.awidth.W))
  val rs2 = Input(UInt(p.awidth.W))
  val ps1 = Output(UInt(p.pwidth.W))
  val ps2 = Output(UInt(p.pwidth.W))
}

// Register map write port.
class MapWritePort(implicit p: Param) extends Bundle {
  val rd = Input(UInt(p.awidth.W))
  val pd = Input(UInt(p.pwidth.W))
  val en = Input(Bool())
}

// Register freelist allocation port.
class RflAllocPort(implicit p: Param) extends Bundle {
  val en = Input(Bool())
  val idx = Valid(UInt(p.pwidth.W))
}

// Register freelist release port.
class RflFreePort(implicit p: Param) extends Bundle {
  val idx = UInt(p.pwidth.W)
}


// ============================================================================
// Modules


// Chained priority encoder selecting up to 'width' bits from some value.
object ChainedPriorityEncoderOH {
  def apply(value: UInt, width: Int) = {
    val res  = Wire(Vec(width, UInt(value.getWidth.W)))
    var mask = value
    for (i <- 0 until width) {
      res(i) := PriorityEncoderOH(mask)
      mask    = mask & ~res(i)
    }
    res
  }
}

// Logic wrapping a list of free physical registers.
class RegisterFreeList(implicit p: Param)
  extends Module 
{
  val alc = IO(Vec(p.dec_width, new RflAllocPort))

  // The freelist consists of one bit for each physical register.
  // The first register (physical register 0) is always reserved.
  val frl = RegInit(UInt(p.num_preg.W), ~(1.U(p.num_preg.W)))

  // Defaults
  for (i <- 0 until p.dec_width) {
    alc(i).idx.valid := false.B
    alc(i).idx.bits  := 0.U
  }

  // Determine the current set of free registers available to allocate.
  val free_preg  = ChainedPriorityEncoderOH(frl, p.dec_width)

  // Drive free register indexes on each allocation port.
  // An index is valid only when it is nonzero.
  //
  // NOTE: The available free register indexes are always being driven, 
  // regardless of whether or not 'en' is high on the corresponding port.
  for (i <- 0 until p.dec_width) {
    alc(i).idx.valid := free_preg(i).orR
    alc(i).idx.bits  := OHToUInt(free_preg(i))
  }

  // Mask off only the set of bits that were allocated this cycle.
  //
  // The 'en' signal on each port indicates whether or not an entry
  // should be consumed (and masked off starting on the next cycle).
  val free_mask  = (alc zip free_preg) map { 
    case (a, f) => f & Fill(p.num_preg, a.en)
  } reduce(_|_)

  // Compute the value of the freelist for the next cycle.
  frl := (frl & ~free_mask)

  printf("[Freelist]: frl=%x\n", frl)
  for (i <- 0 until p.dec_width) {
    printf("[Freelist]: alc[%x] valid=%b idx=%x\n", i.U,
      alc(i).idx.valid,
      alc(i).idx.bits,
    )
  }
}

// Map from architectural registers to physical registers.
class RegisterMap(implicit p: Param) extends Module {
  val rp  = IO(Vec(p.dec_width, new MapReadPort))
  val wp  = IO(Vec(p.dec_width, new MapWritePort))
  val map = Reg(Vec(p.num_areg, UInt(p.pwidth.W)))

  // Defaults
  for (port <- rp) {
    port.ps1 := 0.U
    port.ps2 := 0.U
  }
  // Connect read ports
  for (port <- rp) {
    port.ps1 := Mux(port.rs1 =/= 0.U, map(port.rs1), 0.U)
    port.ps2 := Mux(port.rs2 =/= 0.U, map(port.rs2), 0.U)
  }
  // Connect write ports
  for (port <- wp) {
    when (port.en && port.rd =/= 0.U) {
      map(port.rd) := port.pd
    }
  }
}

// Register rename unit.
//
// FIXME: You need bypassing on the register map read/write ports in order
//        to deal with having multiple instructions per decode packet
//        (or drum up some other kind of solution).
//
// FIXME: May be useful to think about doing this across multiple cycles?
// FIXME: Elaborate on preserving the original program order.
//
// # Overview
// Register renaming removes all WAR and WAW dependences, leaving only RAW
// dependences. The result values for all in-flight instructions are only 
// associated to a single unique storage location. 
//
// Without the potential aliasing between architectural storage locations, 
// programs naturally inherit a "dataflow graph" structure: nodes in the graph 
// are instructions/computations, and the edges between instructions are the
// RAW dependences. 
//
// Conceptually, this is a way of obviating *paths of computations that can be 
// computed independently of one another*, making the problem more amenable to
// being parallelized. The underlying order depends only on the availability 
// of values that satisfy RAW dependences.
//
class RegisterRename(implicit p: Param) extends Module {
  val rfl_alc = IO(Vec(p.dec_width, Flipped(new RflAllocPort)))
  val map_rp  = IO(Vec(p.dec_width, Flipped(new MapReadPort)))
  val map_wp  = IO(Vec(p.dec_width, Flipped(new MapWritePort)))
  val req     = IO(Flipped(Decoupled(new DecodePacket)))

  // Determine which instructions need to allocate a register result
  val rr_en   = Wire(Vec(p.dec_width, Bool()))
  for (i <- 0 until p.dec_width) {
    rr_en(i) := Mux(req.bits.inst(i).rd =/= 0.U, true.B, false.B)
  }

  // True when at least one instruction uses a register result
  val need_alc = rr_en.reduce(_|_)

  // Allocation is successful when the freelist presents enough valid free
  // registers to cover the number of requested allocations
  val alc_ok  = (rfl_alc zip rr_en) map {
    case (pd, rr_req) => pd.idx.valid & rr_req
  } reduce(_|_)

  // Rename is stalled in cases where allocation would not succeed.
  // If we don't need to allocate, there's no reason for us to stall.
  val alc_stall = (need_alc && !alc_ok)

  // Drive default outputs
  for (i <- 0 until p.dec_width) {
    rfl_alc(i).en := false.B
    map_rp(i).rs1 := 0.U
    map_rp(i).rs2 := 0.U

    map_wp(i).en  := false.B
    map_wp(i).rd  := 0.U
    map_wp(i).pd  := 0.U
  }

  // FIXME: Is it okay to make 'ready' depend on 'valid' here?
  req.ready := (!alc_stall && req.valid)

  // Allocate and bind destination registers.
  when (req.fire) {
    for (i <- 0 until p.dec_width) {
      rfl_alc(i).en := rr_en(i)
      map_wp(i).en  := rr_en(i)
      map_wp(i).rd  := req.bits.inst(i).rd
      map_wp(i).pd  := rfl_alc(i).idx.bits
    }
  }

  //val req_rd  = req.bits.inst map (_.rd)
  //val req_rs1 = req.bits.inst map (_.rs1)
  //val req_rs2 = req.bits.inst map (_.rs2)

  // Resolve source registers.
  //
  // FIXME: 
  // When allocated registers for an instruction are used by some other
  // instruction in this packet, we should directly forward the physical
  // register names instead of reading them from the map.
  //
  when (req.fire) {
    for (i <- 0 until p.dec_width) {

      map_rp(i).rs1 := req.bits.inst(i).rs1
      map_rp(i).rs2 := req.bits.inst(i).rs2
    }
  }

  for (i <- 0 until p.dec_width) {
    printf("[Rename]: req[%x] pc=%x [%x,%x,%x] => [%x,%x,%x]\n",
      i.U, req.bits.pc + (i*4).U, 
      req.bits.inst(i).rd,
      req.bits.inst(i).rs1,
      req.bits.inst(i).rs2,
      rfl_alc(i).idx.bits,
      map_rp(i).ps1,
      map_rp(i).ps2,
    )
  }
  printf("[Rename]: alc_stall=%b\n", alc_stall)


}


// ============================================================================
// Top module


// Variables used to parameterize different aspects of the machine. 
case class Param(
  num_areg: Int = 32, // Number of architectural registers
  num_preg: Int = 64, // Number of physical registers
  rob_sz:   Int = 32, // Number of reorder buffer entries
  dec_width:Int = 2,  // Number of instructions in a decode packet
  opq_sz:   Int = 4,  // Number of buffered decode packets
) {
  val robwidth: Int = log2Ceil(rob_sz)
  val awidth:   Int = log2Ceil(num_areg)
  val pwidth:   Int = log2Ceil(num_preg)
}

class PipelineBackend extends Module {
  implicit val p = Param()

  // Decoded instruction[s] and the associated program counter
  val req = IO(Flipped(Decoupled(new DecodePacket)))

  // Buffer between the frontend and register rename
  val opq = Module(new Queue(new DecodePacket, p.opq_sz))

  val rfl = Module(new RegisterFreeList)
  val map = Module(new RegisterMap)
  val rrn = Module(new RegisterRename)

  for (i <- 0 until p.dec_width) {
    printf("[Decode]: req[%x] pc=%x rd=%x rs1=%x rs2=%x\n",
      i.U,
      req.bits.pc + (i*4).U, req.bits.inst(i).rd,
      req.bits.inst(i).rs1, req.bits.inst(i).rs2)
  }

  // Decode packets move from the front-end to the OPQ.
  opq.io.enq.valid := req.valid
  opq.io.enq.bits  := req.bits
  req.ready        := (opq.io.enq.ready)

  // Decode packets are send from the OPQ to register rename.
  rrn.req.valid    := opq.io.deq.valid
  rrn.req.bits     := opq.io.deq.bits
  opq.io.deq.ready := rrn.req.ready

  // Register rename sends allocation requests to the freelist.
  rrn.rfl_alc <> rfl.alc
  // Register rename uses the map to resolve register operands.
  rrn.map_rp  <> map.rp
  rrn.map_wp  <> map.wp

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
        Instr(NOP, 0, 0, 0, None),
        Instr(NOP, 0, 0, 0, None),
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
            _.inst(0) -> prog(pc),
            _.inst(1) -> prog(pc+1),
          ))
          dut.req.valid.poke(true.B)
          pc += 2
        } 
        else {
          dut.req.valid.poke(false.B)
          dut.req.bits.poke((new DecodePacket).Lit(
            _.pc   -> 0xdeadbeefL.U,
            _.inst(0) -> Instr(),
            _.inst(1) -> Instr(),
          ))
        }
        clk.step()
        cyc += 1
      }
    }
  }
}


