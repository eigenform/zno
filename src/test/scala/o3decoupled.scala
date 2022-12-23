import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.experimental._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._
import org.scalatest.flatspec.AnyFlatSpec

import zno.common._

// FIXME: Move this into src/main/scala/riscv at some point
// NOTE: Try to make this legible in more ways than one.

// ============================================================================
// Datatypes and bundle declarations

// Stand-in for a typical "decoded instruction"
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
  val op   = InstType()         // Operation
  val pc   = UInt(32.W)         // Program counter
  val rd   = UInt(p.awidth.W)   // Architectural destination register
  val rs1  = UInt(p.awidth.W)   // Architectural source register #1
  val rs2  = UInt(p.awidth.W)   // Architectural source register #2
  val pd   = UInt(p.pwidth.W)   // Physical destination register
  val ps1  = UInt(p.pwidth.W)   // Physical source register #1
  val ps2  = UInt(p.pwidth.W)   // Physical source register #2
  val ridx = UInt(p.robwidth.W) // Reorder buffer entry index
  val imm  = UInt(32.W)         // Immediate value
  val imm_en = Bool()           // Immediate enable

  // Determine whether or not this uop has a register result
  def has_rr(): Bool = {
    this.rd =/= 0.U
  }

  // Determine whether or not this uop is schedulable (must be executed).
  // "Non-scheduable" operations are only tracked by the ROB.
  def schedulable(): Bool = {
    this.op =/= InstType.NOP
  }

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
//
// FIXME: You're never releasing any physical registers.
//
class RegisterFreeList(implicit p: Param)
  extends Module 
{
  val alc = IO(Vec(p.dec_width, new RflAllocPort))
  val num_free = IO(Output(UInt(log2Ceil(p.num_preg+1).W)))

  // The freelist consists of one bit for each physical register.
  // The first register (physical register 0) is always reserved.
  val frl = RegInit(UInt(p.num_preg.W), ~(1.U(p.num_preg.W)))

  num_free := PopCount(frl)

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
//
// FIXME: This is the "front" register map; you're also going to need another
// register map for the committed state. 
//
class RegisterMap(implicit p: Param) extends Module {
  val rp     = IO(Vec((p.dec_width * 2), new MapReadPort))
  val wp     = IO(Vec(p.dec_width, new MapWritePort))
  val regmap = Reg(Vec(p.num_areg, UInt(p.pwidth.W)))

  val zeroes = IO(Output(UInt(p.num_areg.W)))
  val is_z   = regmap.map(preg => preg === 0.U)
  zeroes    := OHToUInt(is_z)

  // Defaults
  for (port <- rp) {
    port.preg := 0.U
  }
  // Connect read ports
  for (port <- rp) {
    port.preg := Mux(port.areg =/= 0.U, regmap(port.areg), 0.U)
  }
  // Connect write ports
  for (port <- wp) {
    when (port.en && port.rd =/= 0.U) {
      regmap(port.rd) := port.pd
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

  val req    = IO(Flipped(new Packet(new MicroOp, p.dec_width)))
  val res    = IO(new Packet(new MicroOp, p.dec_width))

  // The number of source register references in a packet
  val num_src = (p.dec_width * 2)
  val uop_in   = req.data

  // The set of all source register names in this packet.
  val src_reg = uop_in.map(uop => Seq(uop.rs1, uop.rs2)).flatten
  // Set of *resolved* [physical] source register names.
  val src_ps  = Wire(Vec(num_src, UInt(p.pwidth.W)))
  // Set of allocated [physical] destination register names.
  val src_pd  = Wire(Vec(p.dec_width, UInt(p.pwidth.W)))


  // Bits indicating which instructions have a register result.
  // FIXME: Right now this only depends on RD being non-zero.
  val rr_en   = uop_in.map(uop => uop.has_rr())
  //val rr_en   = Wire(Vec(p.dec_width, Bool()))
  //for (i <- 0 until p.dec_width) {
  //  rr_en(i) := (req.bits.inst(i).rd =/= 0.U)
  //}

  // FIXME: Is it okay to make 'req.ready' depend on 'req.valid' here?
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

  // Allocate and bind destination registers.
  // When an instruction in the packet uses a register result, we need to:
  //  (a) Tell the freelist that we're consuming a register
  //  (b) Bind 'rd' to the physical register (by writing to the map)

  for (i <- 0 until p.dec_width) {
    rfl_alc(i).en := false.B
    src_pd(i)     := 0.U
    map_wp(i).en  := false.B
    map_wp(i).rd  := 0.U
    map_wp(i).pd  := 0.U
  }

  when (req.fire) {
    for (i <- 0 until p.dec_width) {
      rfl_alc(i).en := rr_en(i)
      src_pd(i)     := Mux(rr_en(i), rfl_alc(i).idx.bits, 0.U)
      map_wp(i).en  := rr_en(i)
      map_wp(i).rd  := uop_in(i).rd
      map_wp(i).pd  := src_pd(i)
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

  val matches = Wire(Vec(p.dec_width, Vec(num_src, Bool())))
  for (dst_iidx <- 0 until p.dec_width) {
    matches(dst_iidx) := src_reg.zipWithIndex map {
      case (sr,sr_idx) => { 
        val sr_iidx = sr_idx / 2
        if (sr_iidx <= dst_iidx) {
          false.B
        } 
        else {
          (sr === uop_in(dst_iidx).rd) && (sr =/= 0.U)
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

  val byps = Wire(Vec(num_src, new SrcByp))
  for (i <- 0 until num_src) {
    byps(i).en  := false.B
    byps(i).idx := 0.U
  }

  when (req.fire) {
    for (sr_idx <- 0 until num_src) {

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
  for (i <- 0 until num_src) {
    printf("byps[%x] en=%b idx=%x\n", i.U, byps(i).en, byps(i).idx)
  }

  // Resolve the physical names for all source registers.
  //
  // 1. Only drive the read ports when we cannot resolve the value
  //    from other providing instructions in the packet.
  // 2. Resolve the physical register from a previous allocation,
  //    otherwise, read it from the register map.
  //

  for (i <- 0 until num_src) {
    map_rp(i).areg := 0.U
    src_ps(i)      := 0.U
  }
  when (req.fire) {
    for (i <- 0 until num_src) {
      map_rp(i).areg := Mux(byps(i).en, 0.U, src_reg(i))
      src_ps(i)      := Mux(byps(i).en, 
        rfl_alc(byps(i).idx).idx.bits, 
        map_rp(i).preg
      )
    }
  }

  // Drive output micro-ops

  val uop_out = res.data
  res.len    := 0.U
  for (i <- 0 until p.dec_width) {
    uop_out(i).op     := InstType.NONE
    uop_out(i).pc     := 0xdeadbeefL.U
    uop_out(i).rd     := 0.U
    uop_out(i).rs1    := 0.U
    uop_out(i).rs2    := 0.U
    uop_out(i).pd     := 0.U
    uop_out(i).ps1    := 0.U
    uop_out(i).ps2    := 0.U
    uop_out(i).ridx   := 0.U
    uop_out(i).imm    := 0xdeadbeefL.U
    uop_out(i).imm_en := false.B
  }

  when (req.fire) {
    res.len := req.len
    for (i <- 0 until p.dec_width) {
      val ps1_idx = (i * 2)
      val ps2_idx = (i * 2) + 1
      uop_out(i).op   := uop_in(i).op
      uop_out(i).pc   := uop_in(i).pc
      uop_out(i).rd   := uop_in(i).rd
      uop_out(i).rs1  := uop_in(i).rs1
      uop_out(i).rs2  := uop_in(i).rs2
      uop_out(i).pd   := src_pd(i)
      uop_out(i).ps1  := src_ps(ps1_idx)
      uop_out(i).ps2  := src_ps(ps2_idx)
      uop_out(i).ridx := 0.U
    }
  }

  for (i <- 0 until p.dec_width) {
    printf("[Rename]: req[%x] v=%b pc=%x [%x,%x,%x]\n",
      i.U, (i.U < req.len),
      uop_in(i).pc, uop_in(i).rd, uop_in(i).rs1, uop_in(i).rs2,
    )
    printf("[Rename]: res[%x] v=%b pc=%x [%x,%x,%x]\n",
      i.U, (i.U < req.len),
      uop_out(i).pc, uop_out(i).pd, uop_out(i).ps1, uop_out(i).ps2,
    )


  }
  printf("[Rename]: alc_stall=%b\n", alc_stall)
}

class ReorderBuffer(implicit p: Param) extends Module {
  val num_free = IO(Output(UInt(log2Ceil(p.rob_sz+1).W)))
  num_free := p.rob_sz.U

  //val io = IO(new Bundle {
  //  val ap = Vec(1, new ROBAllocPort)
  //  val wp = Vec(1, new ROBWritePort)
  //  val rp = Vec(1, Valid(new ROBEntry))
  //})

  //val head = RegInit(0.U(robwidth.W))
  //val tail = RegInit(0.U(robwidth.W))
  //val data = RegInit(VecInit(Seq.fill(rob_sz)(ROBEntry())))
  //val can_alloc = ((head + 1.U) =/= tail)
  //val is_empty  = (head === tail)

  //io.ap(0).idx.valid := false.B
  //io.ap(0).idx.bits  := 0.U
  //io.rp(0).valid := false.B
  //io.rp(0).bits  := ROBEntry()

  //// Allocate a new entry
  //when (io.ap(0).en && can_alloc) {
  //  io.ap(0).idx.valid := true.B
  //  io.ap(0).idx.bits  := head
  //  data(head).rd      := io.ap(0).rd
  //  data(head).pd      := io.ap(0).pd
  //  data(head).done    := false.B
  //  head               := head + 1.U
  //}

  //// Mark an entry as complete
  //when (io.wp(0).en) {
  //  data(io.wp(0).idx).done := true.B
  //}

  //// Release an entry
  //when (!is_empty && data(tail).done) {
  //  io.rp(0).valid := true.B
  //  io.rp(0).bits  := data(tail)
  //  data(tail)     := ROBEntry()
  //  tail           := tail + 1.U
  //}
  //printf("[ROB] head=%x tail=%x \n",
  //  head, tail
  //)
}

class SchedulerQueue(implicit p: Param) extends Module {
  val num_free = IO(Output(UInt(log2Ceil(p.sch_sz+1).W)))
  num_free := p.sch_sz.U


}

class DispatchResourceCtrl(implicit p: Param) extends Module {
  // Connection to the micro-op queue
  val opq = IO(new FIFOConsumerIO(new MicroOp, p.dec_width))
  // Dispatchable packet of micro-ops 
  val dis_pkt = IO(new Packet(new MicroOp, p.dec_width))

  // Signals indicating resource utilization in the backend
  val rfl_free = IO(Input(UInt(log2Ceil(p.num_preg+1).W)))
  val sch_free = IO(Input(UInt(log2Ceil(p.sch_sz+1).W)))
  val rob_free = IO(Input(UInt(log2Ceil(p.rob_sz+1).W)))

  // Determine how many micro-ops we can consume from 'opq'
  val rfl_free_tbl = (0 until p.dec_width).map(i => (i.U < rfl_free))
  val sch_free_tbl = (0 until p.dec_width).map(i => (i.U < sch_free))
  val rob_free_tbl = (0 until p.dec_width).map(i => (i.U < rob_free))
  val rr_req_tbl = opq.data.zipWithIndex.map({
    case (op,idx) => (op.has_rr() && (idx.U < opq.len))
  })
  val sc_req_tbl = opq.data.zipWithIndex.map({
    case (op,idx) => (op.schedulable() && (idx.U < opq.len))
  })
  val rb_req_tbl = (0 until p.dec_width).map({
    case (idx) => (idx.U < opq.len)
  })
  val rr_ok_tbl = (rr_req_tbl zip rfl_free_tbl) map {
    case (req, free) => (!req || req && free)
  }
  val sc_ok_tbl = (sc_req_tbl zip sch_free_tbl) map {
    case (req, free) => (!req || req && free)
  }
  val rb_ok_tbl = (rb_req_tbl zip rob_free_tbl) map {
    case (req, free) => (!req || req && free)
  }
  val ok_tbl = (rr_ok_tbl zip sc_ok_tbl zip rb_ok_tbl) map {
    case ((rr, sc), rb) => rr && sc && rb
  }

  // Tell the micro-op queue how many entries we've consumed this cycle
  val num_ok = PopCount(ok_tbl)
  opq.take := num_ok

  // Drive only the micro-ops that we're allowed to consume 
  for (idx <- 0 until p.dec_width) {
    when (idx.U < num_ok) {
      dis_pkt.data(idx) := opq.data(idx)
    } 
    .otherwise {
      dis_pkt.data(idx) := (0.U).asTypeOf(new MicroOp)
    }
  }
  dis_pkt.len := num_ok


  printf("[Disp1]: free_preg=%d free_sch=%d free_rob=%d\n",
    rfl_free, sch_free, rob_free)
  for (i <- 0 until p.dec_width) {
    printf("[Disp1]: req[%x] v=%b pc=%x rd=%x rs1=%x rs2=%x\n",
      i.U, (i.U < opq.len),
      opq.data(i).pc, opq.data(i).rd,
      opq.data(i).rs1, opq.data(i).rs2
    )
    printf("  op[%x] has_rr=%b sched=%b ok=%b\n", 
      i.U, rr_req_tbl(i), sc_req_tbl(i), ok_tbl(i)
    )
  }

}



// ============================================================================
// Top module


// Variables used to parameterize different aspects of the machine. 
case class Param(
  num_areg: Int = 32, // Number of architectural registers
  num_preg: Int = 64, // Number of physical registers
  rob_sz:   Int = 32, // Number of reorder buffer entries
  sch_sz:   Int = 8,
  dec_width:Int = 4,  // Number of instructions in a decode packet
  opq_sz:   Int = 32, // Number of buffered micro-ops
) {
  val robwidth: Int = log2Ceil(rob_sz)
  val awidth:   Int = log2Ceil(num_areg)
  val pwidth:   Int = log2Ceil(num_preg)
}

class PipelineBackend extends Module {
  implicit val p = Param()

  val req = IO(Flipped(Decoupled(new DecodePacket)))

  val opq = Module(new DecouplingFIFO(new MicroOp, 
    width=p.dec_width, 
    entries=p.opq_sz
  ))
  val dis_ctrl = Module(new DispatchResourceCtrl)
  val rfl = Module(new RegisterFreeList)
  val map = Module(new RegisterMap)
  val rrn = Module(new RegisterRename)
  val rob = Module(new ReorderBuffer)
  val sch = Module(new SchedulerQueue)


  for (i <- 0 until p.dec_width) {
    printf("[Decode]: req[%x] pc=%x rd=%x rs1=%x rs2=%x\n",
      i.U,
      req.bits.pc + (i*4).U, req.bits.inst(i).rd,
      req.bits.inst(i).rs1, req.bits.inst(i).rs2
    )
  }

  // ---------------------------------------------------------------------
  // Buffering between decode and dispatch.
  //
  // FIXME: Stall front-end when the OPQ cannot accept a full decode packet.
  // 'dec_pkt.lim' indicates the number of ops that can be enqueued. 

  // Wires connecting from decode packet to the OPQ
  val dec_pkt = Wire(new FIFOProducerIO(new MicroOp, p.dec_width))

  // Always try to send a whole decode packet to the OPQ
  dec_pkt.len := p.dec_width.U
  for (idx <- 0 until p.dec_width) {
    dec_pkt.data(idx).op     := req.bits.inst(idx).op
    dec_pkt.data(idx).pc     := req.bits.pc + (idx * 4).U
    dec_pkt.data(idx).rd     := req.bits.inst(idx).rd
    dec_pkt.data(idx).rs1    := req.bits.inst(idx).rs1
    dec_pkt.data(idx).rs2    := req.bits.inst(idx).rs2
    dec_pkt.data(idx).pd     := 0.U
    dec_pkt.data(idx).ps1    := 0.U
    dec_pkt.data(idx).ps2    := 0.U
    dec_pkt.data(idx).ridx   := 0.U
    dec_pkt.data(idx).imm    := req.bits.inst(idx).imm
    dec_pkt.data(idx).imm_en := req.bits.inst(idx).imm_en
  }

  // Decode packets flow into the OPQ.
  opq.enq.data := dec_pkt.data
  opq.enq.len  := dec_pkt.len
  dec_pkt.lim  := opq.enq.lim

  req.ready := true.B

  // ---------------------------------------------------------------------
  // Dispatch / register renaming / resource allocation

  // Dispatch controller consumes micro-ops from the OPQ
  dis_ctrl.rfl_free := rfl.num_free
  dis_ctrl.sch_free := sch.num_free
  dis_ctrl.rob_free := rob.num_free
  dis_ctrl.opq.data := opq.deq.data
  dis_ctrl.opq.len  := opq.deq.len
  opq.deq.take      := dis_ctrl.opq.take

  // Register rename receives a dispatch packet from the dispatch controller,
  // yielding a packet of renamed micro-ops
  val rrn_pkt  = Wire(new Packet(new MicroOp, p.dec_width))
  rrn.req     := dis_ctrl.dis_pkt
  rrn.rfl_alc <> rfl.alc
  rrn.map_rp  <> map.rp
  rrn.map_wp  <> map.wp
  rrn_pkt     := rrn.res

  // ---------------------------------------------------------------------
  // ROB/scheduler allocation

  //rob.alc.bits       := renamed_uops.


}


// ============================================================================
// Testbench

class O3DecoupledFlowSpec extends AnyFlatSpec with ChiselScalatestTester {
  import InstType._
  implicit val p = Param()

  (new chisel3.stage.ChiselStage)
    .emitVerilog(new PipelineBackend(), Array("-td", "rtl"))

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


