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

// Multi-ported register files are already stupidly expensive, but you could 
// also make them *even more expensive* by adding bypass paths directly from 
// write ports to read ports. This allows for reading/writing from some entry 
// in the same clock cycle.

class RegisterFileBypassing(
  awidth: Int, 
  num_rp: Int, 
  num_wp: Int, 
  byp: Boolean,
) extends Module {
  import scala.collection.mutable.ArrayBuffer

  val io = IO(new Bundle {
    val rp = Vec(num_rp, new RFReadPort(awidth))
    val wp = Vec(num_wp, new RFWritePort(awidth))
  })
  val reg = Reg(Vec(1 << awidth, UInt(32.W)))

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
  printf("r1=%x r2=%x r3=%x r4=%x r5=%x\n",
    reg(1), reg(2), reg(3), reg(4), reg(5))
}

class MapReadPort(awidth: Int, pwidth: Int) extends Bundle {
  val areg = Input(UInt(awidth.W))
  val preg = Output(UInt(pwidth.W))
  def drive_defaults(): Unit = {
    this.areg := 0.U
  }
}
class MapAllocPort(awidth: Int, pwidth: Int) extends Bundle {
  val areg = Input(UInt(awidth.W))
  val en   = Input(Bool())
  val preg = Valid(UInt(pwidth.W))
  def drive_defaults(): Unit = {
    this.areg := 0.U
    this.en   := false.B
  }
}

// A map from architectural registers to physical registers.
//
//   (a) Read ports resolve architectural registers to physical registers
//   (b) Allocation ports allocate a physical register and bind it to
//       an architectural register
//
// FIXME: You're not freeing any physical registers.
// When is this supposed to happen?
//
class RegisterMap(awidth: Int, pwidth: Int) extends Module {
  val init = Cat(Fill(63, true.B), false.B)
  val io = IO(new Bundle {
    val rp = Vec(2, new MapReadPort(awidth, pwidth))
    val ap = Vec(1, new MapAllocPort(awidth, pwidth))
  })

  val map       = Reg(Vec(32, UInt(pwidth.W)))
  val oh_alc    = RegInit(UInt(64.W), init)
  val free_idx  = PriorityEncoder(oh_alc.asBools)
  val free_mask = UIntToOH(free_idx) 

  for (ap <- io.ap) {
    ap.preg.valid := false.B
    ap.preg.bits  := 0.U
    when (ap.en && ap.areg =/= 0.U && free_idx =/= 0.U) {
      oh_alc           := oh_alc & ~free_mask
      map(free_idx)    := ap.areg
      ap.preg.valid    := true.B
      ap.preg.bits     := free_idx
    }
  }
  for (rp <- io.rp) {
    rp.preg := Mux(rp.areg === 0.U, 0.U, map(rp.areg))
  }
  printf("oh_alc=%x free_idx=%x free_mask=%x\n", oh_alc, free_idx, free_mask)

}


// Note that the cost of some tricks scales up aggressively as the pipeline 
// width increases; we're only dealing with a single instruction at a time.
//
//   1. Rename architectural registers to physical registers
//   2. Physical register file read
//   3. Execution
//   4. Commit/physical register file write
//
// # Bypass paths from execution units
// Sometimes, the youngest instruction relies on a value that is currently
// being computed in the execution stage, but whose result value has not 
// been latched by the next pipeline register (feeding the commit stage). 
// Instead of stalling, we can use a bypass path from the ALU output to 
// directly provide the value to the dependent instruction.
//
// # Register file bypassing
// The write port on the physical register file is linked directly to both
// read ports. This allows us to resolve situations where the youngest
// instruction depends on a value which is being committed by an older
// instruction during the same cycle.
//
// FIXME: Finish this!
//


class PipelineModelO3(awidth: Int, pwidth: Int) extends Module {

  class RegisterRename extends Module {
    val map_rp  = IO(Vec(2, Flipped(new MapReadPort(awidth, pwidth))))
    val map_ap  = IO(Vec(1, Flipped(new MapAllocPort(awidth, pwidth))))
    val req     = IO(Flipped(Valid(new Instruction(awidth))))
    val res     = IO(Valid(new Instruction(pwidth)))

    map_ap(0).drive_defaults()
    map_rp(0).drive_defaults()
    map_rp(1).drive_defaults()
    res.bits.drive_defaults()

    // Allocate a physical register
    map_ap(0).en   := (req.valid && req.bits.rd =/= 0.U)
    map_ap(0).areg := req.bits.rd
    val alloc_ok    = map_ap(0).preg.valid

    // Rename source architectural registers to physical registers
    val is_lit = (req.bits.op === InstType.LIT)
    map_rp(0).areg := req.bits.rs1
    map_rp(1).areg := Mux(!is_lit, req.bits.rs2, 0.U)

    val pd  = map_ap(0).preg.bits
    val ps1 = map_rp(0).preg
    val ps2 = map_rp(1).preg

    res.valid := (req.valid && alloc_ok)
    when (res.valid && alloc_ok) {
      res.bits.op  := req.bits.op
      res.bits.rd  := pd
      res.bits.rs1 := ps1
      res.bits.rs2 := ps2
      res.bits.imm := req.bits.imm
    }

    printf("[R] valid=%b alloc_ok=%b [%x,%x,%x => %x,%x,%x] imm=%x\n",
      req.valid, alloc_ok, req.bits.rd, req.bits.rs1, req.bits.rs2, 
      pd, ps1, ps2, req.bits.imm,
    )
  }

  class RFRead extends Module {
    val rfrp    = IO(Vec(2, Flipped(new RFReadPort(pwidth))))
    val bypass  = IO(Flipped(Valid(new Result(pwidth))))

    val req     = IO(Flipped(Valid(new Instruction(pwidth))))
    val res     = IO(Valid(new DataPacket(pwidth)))

    rfrp(0).drive_defaults()
    rfrp(1).drive_defaults()
    res.bits.drive_defaults()
    val is_lit = (req.bits.op === InstType.LIT)

    val rs1_match = ((req.bits.rs1 === bypass.bits.rd) && bypass.valid)
    val rs2_match = ((req.bits.rs2 === bypass.bits.rd) && bypass.valid)

    res.valid := req.valid
    when (req.valid) {
      rfrp(0).addr := Mux(rs1_match, 0.U, req.bits.rs1)
      rfrp(1).addr := Mux((is_lit || rs2_match), 0.U, req.bits.rs2)
      res.bits.rd  := req.bits.rd
      res.bits.x   := Mux(rs1_match, bypass.bits.data, rfrp(0).data)
      res.bits.y   := MuxCase(rfrp(1).data, Array(
        (is_lit)    -> req.bits.imm,
        (rs2_match) -> bypass.bits.data,
      ))
    }
    printf("[R] valid=%b pd=%x, ps1[%x]=%x ps2[%x]=%x imm=%x]\n",
      req.valid, req.bits.rd, 
      req.bits.rs1, res.bits.x,
      req.bits.rs2, res.bits.y,
      req.bits.imm,
    )
    printf("    rs1_bypass=%b rs2_bypass=%b\n", rs1_match, rs2_match)
  }

  class ALU extends Module {
    val req     = IO(Flipped(Valid(new DataPacket(pwidth))))
    val res     = IO(Valid(new Result(pwidth)))
    res.valid  := req.valid
    res.bits.drive_defaults()
    when (req.valid) {
      res.bits.data := (req.bits.x + req.bits.y)
      res.bits.rd   := req.bits.rd
    }
    printf("[A] valid=%b pd=%x x=%x y=%x res=%x\n", req.valid,
      req.bits.rd, req.bits.x, req.bits.y, res.bits.data
    )
  }

  class CommitUnit extends Module {
    val wp      = IO(Vec(1, Flipped(new RFWritePort(pwidth))))
    val req     = IO(Flipped(Valid(new Result(pwidth))))
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

  val map    = Module(new RegisterMap(awidth, pwidth))
  val rf     = Module(new RegisterFileBypassing(pwidth, 
                      num_rp=2, num_wp=1, byp=true))
  val rrn    = Module(new RegisterRename)
  val rfr    = Module(new RFRead)
  val alu    = Module(new ALU)
  val commit = Module(new CommitUnit)
  val req_in = IO(Flipped(Valid(new Instruction(awidth))))

  val stage_rrn = Reg(Valid(new Instruction(pwidth)))
  val stage_op  = Reg(Valid(new DataPacket(pwidth)))
  val stage_res = Reg(Valid(new Result(pwidth)))

  rrn.map_ap <> map.io.ap
  rrn.map_rp <> map.io.rp
  rfr.rfrp   <> rf.io.rp
  commit.wp  <> rf.io.wp

  stage_rrn  := rrn.res
  stage_op   := rfr.res
  stage_res  := alu.res

  rrn.req    := req_in
  rfr.req    := stage_rrn
  rfr.bypass := alu.res
  alu.req    := stage_op
  commit.req := stage_res
}

