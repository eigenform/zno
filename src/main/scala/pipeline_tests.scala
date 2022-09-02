package zno.pipeline_tests

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._

// Different ways of organizing a generic "instruction pipeline."
//
// The instruction set here is intentionally simple, containing only two kinds
// of operation on data (add-register and add-immediate). I'm also assuming
// that there's no feedback for control-flow: we're only feeding the model 
// with a simple list of instructions.
//

object InstType extends ChiselEnum { val LIT, ADD, NONE = Value }
class Instruction(awidth: Int) extends Bundle {
  val op  = InstType()      // The type of instruction
  val rd  = UInt(awidth.W)  // Result register index
  val rs1 = UInt(awidth.W)  // Source register #1 index
  val rs2 = UInt(awidth.W)  // Source register #2 index
  val imm = UInt(32.W)      // Immediate data
  def drive_defaults(): Unit = {
    this.op  := InstType.NONE
    this.rd  := 0.U
    this.rs1 := 0.U
    this.rs2 := 0.U
    this.imm := 0.U
  }
}

object Instr {
  def apply(awidth: Int, op: InstType.Type, 
    rd: Int, rs1: Int, rs2: Int, imm: BigInt): Valid[Instruction] = 
  {
    (new Valid(new Instruction(awidth))).Lit(_.valid -> true.B,
      _.bits -> (new Instruction(awidth)).Lit(
        _.op  -> op,
        _.rd  -> rd.U,
        _.rs1 -> rs1.U,
        _.rs2 -> rs2.U,
        _.imm -> imm.U,
      )
    )
  }
  def apply(awidth: Int): Valid[Instruction] = {
    (new Valid(new Instruction(awidth))).Lit(_.valid -> false.B,
      _.bits -> (new Instruction(awidth)).Lit(
        _.op  -> InstType.NONE,
        _.rd  -> 0.U,
        _.rs1 -> 0.U,
        _.rs2 -> 0.U,
        _.imm -> 0xdeadbeefL.U,
      )
    )
  }
}

class RFReadPort(awidth: Int) extends Bundle {
  val addr = Input(UInt(awidth.W))
  val data = Output(UInt(32.W))
  def drive_defaults(): Unit = {
    this.addr := 0.U
  }
}

class RFWritePort(awidth: Int) extends Bundle {
  val addr = Input(UInt(awidth.W))
  val data = Input(UInt(32.W))
  val en   = Input(Bool())
  def drive_defaults(): Unit = {
    this.addr := 0.U
    this.data := 0.U
    this.en   := false.B
  }
}

class RegisterFile(awidth: Int) extends Module {
  val io = IO(new Bundle {
    val rp = Vec(2, new RFReadPort(awidth))
    val wp = Vec(1, new RFWritePort(awidth))
  })
  val reg = Reg(Vec(1 << awidth, UInt(32.W)))
  for (wp <- io.wp)
    when (wp.en && wp.addr =/= 0.U) { 
      reg(wp.addr) := wp.data 
    }
  for (rp <- io.rp)
    rp.data := Mux(rp.addr === 0.U, 0.U, reg(rp.addr))
  printf("r1=%x r2=%x r3=%x r4=%x r5=%x\n",
    reg(1), reg(2), reg(3), reg(4), reg(5))
}


// This pipeline has the following properties:
//
//  (a) Functional units produce output data and a 'valid' signal
//  (b) A pipeline register captures data/valid signals at clock edges
//  (c) Functional units obtain input by sampling a pipeline register
//  (d) The 'valid' signal always propagates downstream
//  (e) Pipeline registers *always* capture output signals every cycle
//
// Q: "What kind of problem does this solve?"
// A: This splits a complex operation into multiple independent, simpler ones.
//    Instead of a single [potentially very long] combinational path,
//    we can distribute work [temporally] across multiple cycles.
//
// Q: "What are the limitations?"
// A: Operations at each stage must be *independent*. Shared state between 
//    stages (i.e. reads and writes to a register file) creates dependences. 
//    Moreover, because the pipeline registers are *always* captured every
//    cycle, we cannot "stall" the pipeline to wait for dependences.
//    There is no strategy for resolving dependences. 
//
// Example:
//
//    Assume an instruction set where there are finitely-many registers
//    which unambiguously refer to a single unique value. Assume that an 
//    instruction in the pipeline reads registers at stage 1, performs some 
//    operation in stage 2, and writes registers at stage 3.
//
//    This creates situations where the semantics of "referring to a register"
//    are not respected in the pipeline, i.e. given:
//
//      [ r1=0x01, r2=0x02, r3=0x00, r4=0x00 ]
//      add r3, r1, r2
//      mov r4, r3
//      cmp r4, #0x03
//
//    This comparison fails because 0x03 is not available in r3 until after
//    three cycles have elapsed. 'mov r4, r3' resolves 'r4=0x00'.
//
class PipelineModelValid extends Module {

  class DataPacket(pwidth: Int) extends Bundle {
    val rd  = UInt(pwidth.W)
    val x   = UInt(32.W)
    val y   = UInt(32.W)
    def drive_defaults(): Unit = {
      this.rd  := 0.U
      this.x   := 0.U
      this.y   := 0.U
    }
  }

  class Result(pwidth: Int) extends Bundle {
    val rd   = UInt(pwidth.W)
    val data = UInt(32.W)
    def drive_defaults(): Unit = {
      this.rd   := 0.U
      this.data := 0.U
    }
  }

  class RFRead extends Module {
    val rp      = IO(Vec(2, Flipped(new RFReadPort(5))))
    val req     = IO(Flipped(Valid(new Instruction(5))))
    val res     = IO(Valid(new DataPacket(5)))
    res.valid  := req.valid
    res.bits.drive_defaults()
    rp(0).drive_defaults()
    rp(1).drive_defaults()
    when (req.valid) {
      rp(0).addr := req.bits.rs1
      rp(1).addr := Mux((req.bits.op === InstType.LIT), 
        0.U, req.bits.rs2
      )

      res.bits.rd := req.bits.rd
      res.bits.x  := rp(0).data
      res.bits.y  := Mux((req.bits.op === InstType.LIT), 
        req.bits.imm, rp(1).data
      )
    }
    printf("[R] valid=%b rd=%x rs1=%x rs2=%x imm=%x\n", req.valid,
      req.bits.rd, req.bits.rs1, req.bits.rs2, req.bits.imm)

  }

  class ALU extends Module {
    val req     = IO(Flipped(Valid(new DataPacket(5))))
    val res     = IO(Valid(new Result(5)))
    res.valid  := req.valid
    res.bits.drive_defaults()
    when (req.valid) {
      res.bits.data := (req.bits.x + req.bits.y)
      res.bits.rd   := req.bits.rd
    }
    printf("[A] valid=%b x=%x y=%x res=%x\n", req.valid,
      req.bits.x, req.bits.y, res.bits.data)
  }

  class CommitUnit extends Module {
    val wp      = IO(Vec(1, Flipped(new RFWritePort(5))))
    val req     = IO(Flipped(Valid(new Result(5))))
    val ok      = IO(Output(Bool()))
    ok         := req.valid
    wp(0).drive_defaults()
    when (req.valid) {
      wp(0).addr := req.bits.rd
      wp(0).data := req.bits.data
      wp(0).en   := true.B
    }
    printf("[W] valid=%b rd=%x data=%x\n", req.valid,
      req.bits.rd, req.bits.data)
  }

  val req_in    = IO(Flipped(Valid(new Instruction(5))))
  val state     = Module(new RegisterFile(5))
  val reader    = Module(new RFRead)
  val alu       = Module(new ALU)
  val writer    = Module(new CommitUnit)
  val stage_op  = Reg(Valid(new DataPacket(5)))
  val stage_res = Reg(Valid(new Result(5)))

  state.io.rp  <> reader.rp
  state.io.wp  <> writer.wp

  stage_op     := reader.res
  stage_res    := alu.res
  reader.req   := req_in
  alu.req      := stage_op
  writer.req   := stage_res
}

// ---------------------------------------------------------------------------


// Some notes about dynamic scheduling/out-of-order instruction pipelines.
//
// FIXME: Right now, we're only looking at issuing a single instruction at a
// time, and all of our instructions have a single-cycle latency (this largely 
// defeats the practical purpose).
//
// This pipeline is split into the following stages:
//
//   1. Rename architectural registers to physical registers
//   2. Physical register file read (more generally, dependence resolution)
//   3. Execution
//   4. Commit/physical register file write
//
// Register Renaming
// =================
// Register renaming is sufficient to obviate only RAW dependences by removing 
// all WAR and WAW dependences, since the result values for all in-flight
// instructions are associated to a single unique physical storage location. 
//
// Without the potential aliasing between architectural storage locations, the
// program naturally inherits a "dataflow graph" structure. In this form, a 
// program is evaluated according to the ambient topological order given by 
// the availability of values that satisfy RAW dependences.
//
// There are two structures we need to maintain:
//
//   1. A map from architectural register names to physical register names
//   2. A list of free physical registers
//
// When an instruction produces a result, we allocate a physical register
// to-be-written to the map when the instruction retires. Until then, the
// physical register only represents a *microarchitecturally-visible* value
// which may be used to satisfy some dependence. At the same time, each of the 
// architectural source registers is resolved into a physical register 
// representing the *architecturally-visible value* by reading from the map. 
//
// Physical registers are released for reuse only after their corresponding 
// architectural register has been re-bound to a different physical register.
//
// About Scheduling
// ================
// The order of a program is defined by the use of architectural registers:
// these are used by a compiler (or a human) to link instructions to one 
// another, in order to specify a more complicated operation on some data.
//
// If you take dataflow order to be the "optimal" representation of a program,
// it becomes more obvious that the static scheduling performed by compilers 
// (targeting modern O3 machines) is a kind of compression: 
//
//   - A program is typically written like a *linear procedure*
//
//   - The program is converted into a single static assignment (SSA) form,
//     where each instruction corresponds to a unique storage location
//
//   - SSA form/dataflow order naturally obviates *paths* of different 
//     computations in a procedure that can be evaluated independently 
//     without respect to one another
//   
//   - A compiler tries to *optimize* this back into a *single* path where
//     (a) the original [procedural] semantics are preserved, (b) the set of
//     storage locations (architectural registers) is limited, and (c) the set
//     of parallel machines [and their availability in] performing all of the 
//     associated operations is limited
//
// In that sense, "dynamic scheduling" machines always attempt to decompress
// the instruction stream into an "instruction window" bounded by the size of
// the physical register file (and also by the size of a reorder buffer).
//
// Reordering Instructions
// =======================
//
// The "meaning" of a program is a list of effects on the architectural state.
// This is not necessarily the same as the list of effects that the DFG order 
// may have on the microarchitectural state, since all of the utility relies 
// on the fact that the order is *temporarily* removed in order to parallelize 
// the associated work.
//
// Architectural effects are serialized by keeping track of all instructions
// in a queue called a "reorder buffer." Only oldest instruction in the buffer
// is allowed to update the mapping from architectural registers to physical
// registers after it has completed execution and committed its result to the 
// physical register file.
//
//   1. At dispatch, rename all source operands into physical register names 
//      using the map. Put the instruction onto the reorder buffer, and onto
//      another queue for instructions whose dependences are not resolved
//      (typically called an "issue queue," or a "reservation station").
//
//   2. At issue, instructions "wake up" for execution when all dependences
//      are resolved. Dependences are "resolved" when they are present in 
//      the physical register file, or when they are held in some bypass path
//      connected directly to an execution unit.
//
//   3. Sometime after an instruction completes, the result is written back
//      to its associated physical register, and the instruction is marked
//      as "complete" in the reorder buffer entry.
//
//   4. When the instruction reaches the end of the reorder buffer *and* the
//      instruction is complete, the map is updated to bind the architectural 
//      destination register to the physical register allocated at dispatch.
//      Then, the reorder buffer entry is released.
//
// Resolving RAW dependences
// =========================
//
// TODO
//
// Bypass paths from execution units
// ---------------------------------
// Sometimes, the youngest instruction relies on a value that is currently
// being computed in the execution stage, but whose result value has not 
// been latched by the next pipeline register (feeding the commit stage). 
// Instead of stalling, we can use a bypass path from the ALU output to 
// directly provide the value to the dependent instruction.
//
// Register file bypassing
// -----------------------
// The write port on the physical register file is linked directly to both
// read ports. This allows us to resolve situations where the youngest
// instruction depends on a value which is being committed by an older
// instruction during the same cycle.
//
// FIXME: Finish this!
//

// NOTE: Maybe smarter to use 'case class' and implicit parameters?
// Avoids the situation where you accidentally try to redefine these?
trait O3Params {
  val num_areg: Int = 32
  val num_preg: Int = 64
  val awidth:   Int = log2Ceil(this.num_areg)
  val pwidth:   Int = log2Ceil(this.num_preg)
  val rob_sz:   Int = 32
  val robwidth: Int = log2Ceil(this.rob_sz)
}

class PipelineModelO3 extends Module with O3Params {

  class MicroOp extends Bundle {
    val op   = InstType()
    val pd   = UInt(awidth.W)
    val ps1  = UInt(awidth.W)
    val ps2  = UInt(awidth.W)
    val ridx = UInt(robwidth.W)
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

  class DataPacket extends Bundle {
    val pd   = UInt(pwidth.W)
    val x    = UInt(32.W)
    val y    = UInt(32.W)
    val ridx = UInt(robwidth.W)
    def drive_defaults(): Unit = {
      this.pd   := 0.U
      this.x    := 0.U
      this.y    := 0.U
      this.ridx := 0.U
    }
  }

  class Result extends Bundle {
    val pd   = UInt(pwidth.W)
    val ridx = UInt(robwidth.W)
    val data = UInt(32.W)
    def drive_defaults(): Unit = {
      this.pd   := 0.U
      this.data := 0.U
      this.ridx := 0.U
    }
  }

  class RegisterFileBypassing(num_rp: Int, num_wp: Int, byp: Boolean) 
    extends Module
  {
    import scala.collection.mutable.ArrayBuffer
    val io = IO(new Bundle {
      val dbg = Vec(num_areg, Output(UInt(32.W)))
      val rp = Vec(num_rp, new RFReadPort(awidth))
      val wp = Vec(num_wp, new RFWritePort(awidth))
    })
    val reg = Reg(Vec(num_areg, UInt(32.W)))
    for (rp <- io.rp) {
      rp.data := Mux(rp.addr === 0.U, 0.U, reg(rp.addr))
      // Probably fine to fully-connect these if the port counts are low?
      if (byp) {
        for (wp <- io.wp) {
          when (wp.en && wp.addr =/= 0.U && wp.addr === rp.addr) {
            rp.data := wp.data
          }
        }
      }

    }
    for (wp <- io.wp) {
      when (wp.en && wp.addr =/= 0.U) { 
        reg(wp.addr) := wp.data
      }
    }
    io.dbg := reg
  }

  class MapReadPort extends Bundle {
    val areg = Input(UInt(awidth.W))
    val preg = Output(UInt(pwidth.W))
    def drive_defaults(): Unit = {
      this.areg := 0.U
    }
  }
  class MapWritePort extends Bundle {
    val areg = Input(UInt(awidth.W))
    val preg = Input(UInt(pwidth.W))
    val en   = Input(Bool())
    def drive_defaults(): Unit = {
      this.areg := 0.U
      this.preg := 0.U
      this.en   := false.B
    }
  }
  class MapAllocPort extends Bundle {
    val areg = Input(UInt(awidth.W))
    val en   = Input(Bool())
    val preg = Valid(UInt(pwidth.W))
    def drive_defaults(): Unit = {
      this.areg := 0.U
      this.en   := false.B
    }
  }
  class MapFreePort extends Bundle {
    val preg = Input(UInt(pwidth.W))
    val en   = Input(Bool())
    def drive_defaults(): Unit = {
      this.preg := 0.U
      this.en   := false.B
    }
  }

  // A map from architectural registers to physical registers.
  //
  //   (a) Read ports resolve an architectural register to a physical register
  //   (b) Allocation ports allocate a physical register
  //   (c) Write ports bind an architectural register to a physical register
  //
  class RegisterMap extends Module {
    val io = IO(new Bundle {
      val dbg = Vec(num_areg, Output(UInt(pwidth.W)))
      val rp = Vec(2, new MapReadPort)
      val ap = Vec(1, new MapAllocPort)
      val wp = Vec(1, new MapWritePort)
    })

    val map       = Reg(Vec(num_areg, UInt(pwidth.W)))
    val alc_init  = Cat(Fill((num_preg - 1), true.B), false.B)
    val oh_alc    = RegInit(UInt(num_preg.W), alc_init)

    // Select a free physical register.
    // FIXME: You actually need to chain many of these together to accomodate
    // for a variable number of allocation ports. This only works when the
    // module has a single port!
    val free_idx  = PriorityEncoder(oh_alc.asBools)
    val free_mask = UIntToOH(free_idx) 

    for (ap <- io.ap) {
      ap.preg.valid := false.B
      ap.preg.bits  := 0.U
      when (ap.en && ap.areg =/= 0.U && free_idx =/= 0.U) {
        oh_alc           := oh_alc & ~free_mask
        ap.preg.valid    := true.B
        ap.preg.bits     := free_idx

        // FIXME: Are we supposed to bind a register at the start?
        // Or does this happen after retire? 
        map(ap.areg)     := free_idx
      }
    }
    for (wp <- io.wp) {
      when (wp.en && wp.preg =/= 0.U && wp.areg =/= 0.U) {
        map(wp.areg) := wp.preg
      }
    }
    for (rp <- io.rp) {
      rp.preg := Mux(rp.areg === 0.U, 0.U, map(rp.areg))
    }
    printf("[MAP] oh_alc=%x free_idx=%x free_mask=%x\n", 
      oh_alc, free_idx, free_mask
    )
    io.dbg := map
  }

  // Resolves architectural register operands into physical registers.
  class RegisterRename extends Module {
    val map_rp  = IO(Vec(2, Flipped(new MapReadPort)))
    val map_ap  = IO(Vec(1, Flipped(new MapAllocPort)))
    val rob_ap  = IO(Vec(1, Flipped(new ROBAllocPort)))

    val req     = IO(Flipped(Valid(new Instruction(awidth))))
    val res     = IO(Valid(new MicroOp))

    map_ap(0).drive_defaults()
    map_rp(0).drive_defaults()
    map_rp(1).drive_defaults()
    rob_ap(0).drive_defaults()
    res.bits.drive_defaults()

    // FIXME: The machine should stall if all allocations aren't successful.

    // Allocate a physical register, and a reorder buffer entry.
    map_ap(0).en    := (req.valid && req.bits.rd =/= 0.U)
    map_ap(0).areg  := req.bits.rd
    val pd_alloc_ok  = map_ap(0).preg.valid
    val pd           = map_ap(0).preg.bits

    // Allocate a reorder buffer entry
    rob_ap(0).en    := pd_alloc_ok
    rob_ap(0).rd    := req.bits.rd
    rob_ap(0).pd    := pd
    val rob_alloc_ok = rob_ap(0).idx.valid
    val rob_idx      = rob_ap(0).idx.bits

    // Rename source architectural registers to physical registers.
    val is_lit = (req.bits.op === InstType.LIT)
    map_rp(0).areg := req.bits.rs1
    map_rp(1).areg := Mux(!is_lit, req.bits.rs2, 0.U)
    val ps1         = map_rp(0).preg
    val ps2         = map_rp(1).preg

    res.valid := (req.valid && pd_alloc_ok && rob_alloc_ok)
    when (req.valid && pd_alloc_ok && rob_alloc_ok) {
      res.bits.op   := req.bits.op
      res.bits.pd   := pd
      res.bits.ps1  := ps1
      res.bits.ps2  := ps2
      res.bits.imm  := req.bits.imm
      res.bits.ridx := rob_idx
    }
    printf("[RRN] valid=%b pd_alloc_ok=%b rob_alloc_ok=%b [%x,%x,%x => %x,%x,%x] imm=%x\n",
      req.valid, pd_alloc_ok, rob_alloc_ok, req.bits.rd, req.bits.rs1, req.bits.rs2, 
      pd, ps1, ps2, req.bits.imm,
    )
  }

  // Reads from the physical register file (or a bypass path). 
  class IssueUnit extends Module {
    val rfrp    = IO(Vec(2, Flipped(new RFReadPort(awidth))))

    val bypass  = IO(Flipped(Valid(new Result)))
    val req     = IO(Flipped(Valid(new MicroOp)))
    val res     = IO(Valid(new DataPacket))

    rfrp(0).drive_defaults()
    rfrp(1).drive_defaults()
    res.bits.drive_defaults()

    val is_lit  = (req.bits.op === InstType.LIT)
    val ps1_byp = ((req.bits.ps1 === bypass.bits.pd) && bypass.valid)
    val ps2_byp = ((req.bits.ps2 === bypass.bits.pd) && bypass.valid)

    res.valid := req.valid
    when (req.valid) {
      rfrp(0).addr  := Mux(ps1_byp, 0.U, req.bits.ps1)
      rfrp(1).addr  := Mux((is_lit || ps2_byp), 0.U, req.bits.ps2)
      res.bits.ridx := req.bits.ridx
      res.bits.pd   := req.bits.pd
      res.bits.x    := Mux(ps1_byp, bypass.bits.data, rfrp(0).data)
      res.bits.y    := MuxCase(rfrp(1).data, Array(
        (is_lit)    -> req.bits.imm,
        (ps2_byp)   -> bypass.bits.data,
      ))
    }
    printf("[ISS] valid=%b pd=%x, ps1[%x]=%x ps2[%x]=%x imm=%x]\n",
      req.valid, req.bits.pd, 
      req.bits.ps1, res.bits.x,
      req.bits.ps2, res.bits.y,
      req.bits.imm,
    )
    printf("      ps1_bypass=%b ps2_bypass=%b\n", ps1_byp, ps2_byp)
  }

  // Performs a simple operation.
  class ALU extends Module {
    val req     = IO(Flipped(Valid(new DataPacket)))
    val res     = IO(Valid(new Result))
    res.valid  := req.valid
    res.bits.drive_defaults()
    when (req.valid) {
      res.bits.data := (req.bits.x + req.bits.y)
      res.bits.pd   := req.bits.pd
      res.bits.ridx := req.bits.ridx
    }
    printf("[ALU] valid=%b pd=%x x=%x y=%x res=%x\n", req.valid,
      req.bits.pd, req.bits.x, req.bits.y, res.bits.data
    )
  }

  // Commits some result value to the physical register file.
  class CommitUnit extends Module {
    val rfwp   = IO(Vec(1, Flipped(new RFWritePort(awidth))))
    val req    = IO(Flipped(Valid(new Result)))
    val rob_wp = IO(Vec(1, Flipped(new ROBWritePort)))
    val ok     = IO(Output(Bool()))

    ok := req.valid
    rfwp(0).drive_defaults()
    rob_wp(0).drive_defaults()

    when (req.valid) {
      rfwp(0).addr     := req.bits.pd
      rfwp(0).data     := req.bits.data
      rfwp(0).en       := true.B
      rob_wp(0).en     := true.B
      rob_wp(0).idx    := req.bits.ridx
    }
    printf("[COM] valid=%b ridx=%x rd=%x data=%x\n", req.valid,
      req.bits.ridx, req.bits.pd, req.bits.data)
  }

  class ROBEntry extends Bundle {
    val rd   = UInt(awidth.W)
    val pd   = UInt(pwidth.W)
    val done = Bool()
  }
  object ROBEntry {
    def apply(): ROBEntry = {
      (new ROBEntry).Lit(
        _.rd   -> 0.U,
        _.pd   -> 0.U,
        _.done -> false.B
      )
    }
  }

  class ROBAllocPort extends Bundle {
    val rd  = Input(UInt(awidth.W))
    val pd  = Input(UInt(pwidth.W))
    val en  = Input(Bool())
    val idx = Output(Valid(UInt(robwidth.W)))
    def drive_defaults(): Unit = {
      this.rd := 0.U
      this.pd := 0.U
      this.en := false.B
    }
  }
  class ROBWritePort extends Bundle {
    val idx = Input(UInt(robwidth.W))
    val en  = Input(Bool())
    def drive_defaults(): Unit = {
      this.idx := 0.U
      this.en  := false.B
    }
  }

  class ReorderBuffer extends Module {
    val io = IO(new Bundle {
      val ap = Vec(1, new ROBAllocPort)
      val wp = Vec(1, new ROBWritePort)
      val rp = Vec(1, Valid(new ROBEntry))
    })

    val head = RegInit(0.U(robwidth.W))
    val tail = RegInit(0.U(robwidth.W))
    val data = RegInit(VecInit(Seq.fill(rob_sz)(ROBEntry())))
    val can_alloc = ((head + 1.U) =/= tail)
    val is_empty  = (head === tail)

    io.ap(0).idx.valid := false.B
    io.ap(0).idx.bits  := 0.U
    io.rp(0).valid := false.B
    io.rp(0).bits  := ROBEntry()

    // Allocate a new entry
    when (io.ap(0).en && can_alloc) {
      io.ap(0).idx.valid := true.B
      io.ap(0).idx.bits  := head
      data(head).rd      := io.ap(0).rd
      data(head).pd      := io.ap(0).pd
      data(head).done    := false.B
      head               := head + 1.U
    }

    // Mark an entry as complete
    when (io.wp(0).en) {
      data(io.wp(0).idx).done := true.B
    }

    // Release an entry
    when (!is_empty && data(tail).done) {
      io.rp(0).valid := true.B
      io.rp(0).bits  := data(tail)
      data(tail)     := ROBEntry()
      tail           := tail + 1.U
    }

    printf("[ROB] head=%x tail=%x \n",
      head, tail
    )

  }

  // Binds a physical register to an architectural register.
  class RetireUnit extends Module {
    val map_wp = IO(Vec(1, Flipped(new MapWritePort)))
    val req    = IO(Vec(1, Flipped(Valid(new ROBEntry))))

    map_wp(0).drive_defaults()

    when (req(0).valid) {
      map_wp(0).en   := true.B
      map_wp(0).areg := req(0).bits.rd
      map_wp(0).preg := req(0).bits.pd
    }

    printf("[RCU] valid=%b rd=%x pd=%x\n", 
      req(0).valid, req(0).bits.rd, req(0).bits.pd)
  }

  val map     = Module(new RegisterMap)
  val rob     = Module(new ReorderBuffer)
  val rf      = Module(new RegisterFileBypassing(num_rp=2, num_wp=1, byp=true))

  val rrn     = Module(new RegisterRename)
  val iss     = Module(new IssueUnit)
  val alu     = Module(new ALU)
  val commit  = Module(new CommitUnit)
  val retire  = Module(new RetireUnit)

  val stall   = IO(Output(Bool()))
  val req_in  = IO(Flipped(Valid(new Instruction(awidth))))
  val res_out = IO(Valid(new Result))

  val stage_iss = Reg(Valid(new MicroOp))
  val stage_alu = Reg(Valid(new DataPacket))
  val stage_res = Reg(Valid(new Result))


  printf("[MAP] r1=%x r2=%x r3=%x r4=%x r5=%x r6=%x\n", 
    map.io.dbg(1), map.io.dbg(2),
    map.io.dbg(3), map.io.dbg(4),
    map.io.dbg(5), map.io.dbg(6),
  )

  printf("[PRF] r1=%x r2=%x r3=%x r4=%x r5=%x r6=%x\n", 
    rf.io.dbg(map.io.dbg(1)), rf.io.dbg(map.io.dbg(2)),
    rf.io.dbg(map.io.dbg(3)), rf.io.dbg(map.io.dbg(4)),
    rf.io.dbg(map.io.dbg(5)), rf.io.dbg(map.io.dbg(6)),
  )

  // Register map ports
  map.io.ap <> rrn.map_ap    // Physical register allocation during rename
  map.io.rp <> rrn.map_rp    // Physical register resolution during rename
  map.io.wp <> retire.map_wp // Physical register binding during retire

  // Reorder buffer ports
  rob.io.ap <> rrn.rob_ap    // ROB allocation during rename
  rob.io.wp <> commit.rob_wp // ROB entries are marked during commit
  rob.io.rp <> retire.req    // Released ROB entries flow to retire unit

  // Register file ports
  rf.io.rp  <> iss.rfrp      // PRF read ports used at issue
  rf.io.wp  <> commit.rfwp   // PRF write ports used at commit

  stage_iss  := rrn.res
  stage_alu  := iss.res
  stage_res  := alu.res

  rrn.req    := req_in
  iss.req    := stage_iss
  iss.bypass := alu.res
  alu.req    := stage_alu
  commit.req := stage_res

  res_out    := stage_res
  stall      := false.B

}









