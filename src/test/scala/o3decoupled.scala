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
  val areg = Input(UInt(p.awidth.W))
  val preg = Output(UInt(p.pwidth.W))
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
  val rp  = IO(Vec((p.dec_width * 2), new MapReadPort))
  val wp  = IO(Vec(p.dec_width, new MapWritePort))
  val map = Reg(Vec(p.num_areg, UInt(p.pwidth.W)))

  // Defaults
  for (port <- rp) {
    port.preg := 0.U
  }
  // Connect read ports
  for (port <- rp) {
    port.preg := Mux(port.areg =/= 0.U, map(port.areg), 0.U)
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
// FIXME: You need some kind of bypassing to deal with having multiple 
// instructions in a single decode packet; have to do some matching to handle
// dependences upfront here.
//
// FIXME: May be useful to think about doing this across multiple cycles?
// FIXME: Elaborate on preserving the original program order.
//
// # Abstract Nonsense
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
// completed independently of one another*, making the problem more amenable to
// being parallelized. The underlying order depends only on the availability 
// of values that satisfy RAW dependences.
//
class RegisterRename(implicit p: Param) extends Module {
  val rfl_alc = IO(Vec(p.dec_width, Flipped(new RflAllocPort)))
  val map_rp  = IO(Vec((p.dec_width * 2), Flipped(new MapReadPort)))
  val map_wp  = IO(Vec(p.dec_width, Flipped(new MapWritePort)))

  val req     = IO(Flipped(Decoupled(new DecodePacket)))
  val insts   = req.bits.inst

  // The set of all source register names in this packet.
  val src_reg = insts.map(inst => Seq(inst.rs1, inst.rs2)).flatten
  // Set of *resolved* [physical] source register names.
  val src_ps  = Wire(Vec(p.dec_width * 2, UInt(p.pwidth.W)))

  // Bits indicating which instructions have a register result.
  // FIXME: Right now this only depends on RD being non-zero.
  val rr_en   = Wire(Vec(p.dec_width, Bool()))
  for (i <- 0 until p.dec_width) {
    rr_en(i) := (req.bits.inst(i).rd =/= 0.U)
  }

  // FIXME: Is it okay to make 'ready' depend on 'valid' here?
  //
  // 1. Allocation is successful when the freelist is presenting us with 
  //    enough valid free registers to cover the requested allocations
  // 2. We need to allocate if at least one instruction has a register result
  // 3. Rename should be stalled in cases where allocation would not succeed.
  //    If we don't need to allocate, there's no reason to stall

  val alc_ok    = (rfl_alc zip rr_en) map { 
    case (pd, rr) => pd.idx.valid & rr 
  } reduce(_|_)
  val need_alc  = rr_en.reduce(_|_)
  val alc_stall = (need_alc && !alc_ok)
  req.ready    := (!alc_stall && req.valid)


  // Allocate and bind destination registers.
  // When an instruction in the packet uses a register result, we need to:
  //  (a) Tell the freelist that we're consuming a register
  //  (b) Bind 'rd' to the physical register (by writing to the map)

  for (i <- 0 until p.dec_width) {
    rfl_alc(i).en := false.B
    map_wp(i).en  := false.B
    map_wp(i).rd  := 0.U
    map_wp(i).pd  := 0.U
  }

  when (req.fire) {
    for (i <- 0 until p.dec_width) {
      rfl_alc(i).en := rr_en(i)
      map_wp(i).en  := rr_en(i)
      map_wp(i).rd  := req.bits.inst(i).rd
      map_wp(i).pd  := rfl_alc(i).idx.bits
    }
  }

  // FIXME: It was hard for me to think about this, and it seems obtuse.
  // Is there an easier/more beautiful way of doing this?
  //
  // Build a table of matches between register operands within the packet.
  // For each instruction in this packet, compare the destination register
  // to all source registers in the packet.
  //
  //      (Match table for 4-wide decode)
  //    -----------+-----+-----+-----+-----+
  //    sr_iidx    |  3  |  2  |  1  |  0  | source register instr. index
  //    -----------+-----+-----+-----+-----+
  //    sr_idx     | 7 6 | 5 4 | 3 2 | 1 0 | source register index
  //    -----------+-----+-----+-----+-----+
  //    dst_iidx 0 | ? ? | ? ? | ? ? | 0 0 | provider instr. #0
  //    dst_iidx 1 | ? ? | ? ? | 0 0 | 0 0 | provider instr. #1
  //    dst_iidx 2 | ? ? | 0 0 | 0 0 | 0 0 | provider instr. #2
  //    dst_iidx 3 | 0 0 | 0 0 | 0 0 | 0 0 | provider instr. #3
  //    -----------+-----+-----+-----+-----+
  //
  // An instruction can only forward its physical destination register to the 
  // instructions that follow it in the packet, ie. the physical destination
  // allocated for inst[1] cannot be used to resolve a dependence for inst[0].

  val matches = Wire(Vec(p.dec_width, Vec(p.dec_width * 2, Bool())))
  for (dst_iidx <- 0 until p.dec_width) {
    matches(dst_iidx) := src_reg.zipWithIndex map {
      case (sr,sr_idx) => { 
        val sr_iidx = sr_idx / 2
        if (sr_iidx <= dst_iidx) {
          false.B
        } 
        else {
          (sr === insts(dst_iidx).rd) && (sr =/= 0.U)
        }
      }
    }
  }

  // For each source register reference, we need some bits that indicate 
  // whether the physical register needs to come directly from an allocation 
  // for a previous instruction within this packet. 
  //
  // In these cases, we cannot resolve the physical register by reading the 
  // register map (I don't think you can just bypass from all map write ports
  // to all map read ports - you need to deal with the order of instructions 
  // in the packet).

  class SrcByp extends Bundle {
    // Set when this source register can be identified with the destination
    // register from an earlier instruction in the packet.
    val en  = Bool()
    // The index of the provider instruction (within the packet).
    val idx = UInt(log2Ceil(p.dec_width).W)
  }

  val byps = Wire(Vec(p.dec_width * 2, new SrcByp))
  for (i <- 0 until p.dec_width * 2) {
    byps(i).en  := false.B
    byps(i).idx := 0.U
  }

  when (req.fire) {
    for (sr_idx <- 0 until (p.dec_width * 2)) {

      // Enabled when a source register matches at least one destination 
      // register (any bit in the column is set)
      byps(sr_idx).en  := matches.map(m => m(sr_idx)) reduce(_|_)

      // Get the index (row number 'dst_iidx') of the provider instruction.
      // The latest definition takes precedence. 
      byps(sr_idx).idx := PriorityMux(
        matches.map(m => m(sr_idx)).reverse,
        (0 until p.dec_width).map(_.U).reverse
      )
    }
  }
  for (i <- 0 until p.dec_width * 2) {
    printf("byps[%x] en=%b idx=%x\n", i.U, byps(i).en, byps(i).idx)
  }

  // Resolve the physical names for all source registers.

  for (i <- 0 until (p.dec_width * 2)) {
    map_rp(i).areg := 0.U
    src_ps(i)      := 0.U
  }


  when (req.fire) {
    for (i <- 0 until p.dec_width) {
      val rs1_sr_idx = (i * 2)
      val rs2_sr_idx = (i * 2) + 1

      // Only drive the read ports when we cannot resolve the value
      // from other providing instructions in the packet
      map_rp(rs1_sr_idx).areg := Mux(byps(rs1_sr_idx).en, 
        0.U, src_reg(rs1_sr_idx)
      )
      map_rp(rs2_sr_idx).areg := Mux(byps(rs2_sr_idx).en, 
        0.U, src_reg(rs2_sr_idx)
      )

      // Resolve the physical register from a previous allocation,
      // otherwise, read it from the register map
      src_ps(rs1_sr_idx) := Mux(byps(rs1_sr_idx).en, 
        rfl_alc(byps(rs1_sr_idx).idx).idx.bits,
        map_rp(rs1_sr_idx).preg
      )
      src_ps(rs2_sr_idx) := Mux(byps(rs2_sr_idx).en, 
        rfl_alc(byps(rs2_sr_idx).idx).idx.bits,
        map_rp(rs2_sr_idx).preg
      )
    }
  }

  for (i <- 0 until p.dec_width) {
    val rp_rs1_idx = (i * 2)
    val rp_rs2_idx = (i * 2) + 1
    printf("[Rename]: req[%x] pc=%x [%x,%x,%x] => [%x,%x,%x]\n",
      i.U, req.bits.pc + (i*4).U, 
      req.bits.inst(i).rd,
      req.bits.inst(i).rs1,
      req.bits.inst(i).rs2,
      rfl_alc(i).idx.bits,
      src_ps(rp_rs1_idx),
      src_ps(rp_rs2_idx),
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
  dec_width:Int = 4,  // Number of instructions in a decode packet
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
        Instr(ADD, 1, 0, 1, None),
        Instr(ADD, 1, 0, 1, None),

        Instr(ADD, 1, 0, 1, None),
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
            _.inst(2) -> prog(pc+2),
            _.inst(3) -> prog(pc+3),
          ))
          dut.req.valid.poke(true.B)
          pc += p.dec_width
        } 
        else {
          dut.req.valid.poke(false.B)
          dut.req.bits.poke((new DecodePacket).Lit(
            _.pc   -> 0xdeadbeefL.U,
            _.inst(0) -> Instr(),
            _.inst(1) -> Instr(),
            _.inst(2) -> Instr(),
            _.inst(3) -> Instr(),
          ))
        }
        clk.step()
        cyc += 1
      }
    }
  }
}


