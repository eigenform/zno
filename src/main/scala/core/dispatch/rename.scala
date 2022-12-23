
package zno.core.dispatch

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._



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
class RegisterRename(implicit p: ZnoParam) extends Module {
  val rfl_alc = IO(Vec(p.id_width, Flipped(new RflAllocPort)))
  val map_rp  = IO(Vec((p.id_width * 2), Flipped(new MapReadPort)))
  val map_wp  = IO(Vec(p.id_width, Flipped(new MapWritePort)))

  val req    = IO(Flipped(new Packet(new Uop, p.id_width)))
  val res    = IO(new Packet(new Uop, p.id_width))

  // The number of source register references in a packet
  val num_src = (p.id_width * 2)
  val uop_in   = req.data

  // The set of all source register names in this packet.
  val src_reg = uop_in.map(uop => Seq(uop.rs1, uop.rs2)).flatten
  // Set of *resolved* [physical] source register names.
  val src_ps  = Wire(Vec(num_src, UInt(p.pwidth.W)))
  // Set of allocated [physical] destination register names.
  val src_pd  = Wire(Vec(p.id_width, UInt(p.pwidth.W)))


  // Bits indicating which instructions have a register result.
  // FIXME: Right now this only depends on RD being non-zero.
  val rr_en   = uop_in.map(uop => uop.rr)
  //val rr_en   = Wire(Vec(p.id_width, Bool()))
  //for (i <- 0 until p.id_width) {
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

  for (i <- 0 until p.id_width) {
    rfl_alc(i).en := false.B
    src_pd(i)     := 0.U
    map_wp(i).en  := false.B
    map_wp(i).rd  := 0.U
    map_wp(i).pd  := 0.U
  }

  when (req.fire) {
    for (i <- 0 until p.id_width) {
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

  val matches = Wire(Vec(p.id_width, Vec(num_src, Bool())))
  for (dst_iidx <- 0 until p.id_width) {
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
    val idx = UInt(log2Ceil(p.id_width).W)
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
        (0 until p.id_width).map(_.U).reverse
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
  for (i <- 0 until p.id_width) {
    uop_out(i).drive_defaults()
  }

  when (req.fire) {
    res.len := req.len
    for (i <- 0 until p.id_width) {
      val ps1_idx = (i * 2)
      val ps2_idx = (i * 2) + 1
      uop_out(i).rd   := uop_in(i).rd
      uop_out(i).rs1  := uop_in(i).rs1
      uop_out(i).rs2  := uop_in(i).rs2
      uop_out(i).pd   := src_pd(i)
      uop_out(i).ps1  := src_ps(ps1_idx)
      uop_out(i).ps2  := src_ps(ps2_idx)
      uop_out(i).rid  := 0.U
    }
  }

  //for (i <- 0 until p.id_width) {
  //  printf("[Rename]: req[%x] v=%b pc=%x [%x,%x,%x]\n",
  //    i.U, (i.U < req.len),
  //    uop_in(i).pc, uop_in(i).rd, uop_in(i).rs1, uop_in(i).rs2,
  //  )
  //  printf("[Rename]: res[%x] v=%b pc=%x [%x,%x,%x]\n",
  //    i.U, (i.U < req.len),
  //    uop_out(i).pc, uop_out(i).pd, uop_out(i).ps1, uop_out(i).ps2,
  //  )
  //}
  //printf("[Rename]: alc_stall=%b\n", alc_stall)
}


