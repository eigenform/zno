
package zno.core.mid

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._


// For each source register in a group of macro-ops, determines the index of 
// an associated "provider" macro-op in the group (if one exists).
//
// When an architectural source matches an architectural destination used
// by an older op in the group, we cannot use the register map read port to 
// resolve the name of the physical source - instead, we need to forward
// the name of the newly-allocated physical destination. 
//
// For each op, check all subsequent ops - ie. for a group of 8 ops where
// index '0' is the oldest op in the group, the matrix of possible dependences
// looks like this:
// 
// Provider: 0....... .1...... ..2..... ...3.... ....4... .....5.. ......6.
// Consumer: .1234567 ..234567 ...34567 ....4567 .....567 ......67 .......7
//
// Forwarding occurs when the following conditions are met: 
//
//  - The consumer has a valid source register (rs1 or rs2)
//  - The provider has a valid destination register
//  - The source and destination registers match
//
// When there are multiple ops that qualify as a provider, the *youngest* op 
// takes precedence. 
//
class LocalDependenceUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val mops = Input(Vec(p.dec_bw, new MacroOp))
    val rs1_provider_idx = Output(Vec(p.dec_bw, Valid(p.MopIdx())))
    val rs2_provider_idx = Output(Vec(p.dec_bw, Valid(p.MopIdx())))
  })

  val mops     = io.mops
  val mop_rr   = mops.map(mop => mop.rr)
  val mop_rd   = mops.map(mop => mop.rd)
  val mop_rs1  = mops.map(mop => mop.rs1)
  val mop_rs2  = mops.map(mop => mop.rs2)
  val mop_src1 = mops.map(mop => mop.src1)
  val mop_src2 = mops.map(mop => mop.src2)

  // For each [consumer] op, a bitvector indicating which *previous* ops in 
  // the group are candidate "providers" for rs{1,2} physical names
  val rs1_match       = Wire(Vec(p.dec_bw, Vec(p.dec_bw, Bool())))
  val rs2_match       = Wire(Vec(p.dec_bw, Vec(p.dec_bw, Bool())))
  for (i <- 0 until p.dec_bw) {
    for (j <- 0 until p.dec_bw) {
      rs1_match(i)(j) := false.B
      rs2_match(i)(j) := false.B
    }
  }

  // For each [consumer] op, does a "provider" exist for rs{1,2}?
  val rs1_match_exists = Wire(Vec(p.dec_bw, Bool()))
  val rs2_match_exists = Wire(Vec(p.dec_bw, Bool()))
  for (i <- 0 until p.dec_bw) {
    rs1_match_exists(i) := false.B
    rs2_match_exists(i) := false.B
  }

  // For each [consumer] op, the index of the "provider" for rs{1,2}
  val rs1_match_idx   = Wire(Vec(p.dec_bw, p.MopIdx()))
  val rs2_match_idx   = Wire(Vec(p.dec_bw, p.MopIdx()))
  for (i <- 0 until p.dec_bw) {
    rs1_match_idx(i) := 0.U
    rs2_match_idx(i) := 0.U
  }

  // FIXME: Perhaps there's a clearer way to write this..
  for (prv_idx <- 0 until p.dec_bw-1) {
    for (src_idx <- prv_idx+1 until p.dec_bw) {
      val has_rr    = (mop_rr(prv_idx))
      val has_rs1   = (mop_src1(src_idx) === SrcType.S_REG)
      val has_rs2   = (mop_src2(src_idx) === SrcType.S_REG)
      val match_rs1 = (mop_rs1(src_idx) === mop_rd(prv_idx))
      val match_rs2 = (mop_rs2(src_idx) === mop_rd(prv_idx))
      rs1_match(src_idx)(prv_idx) := (has_rr && has_rs1 && match_rs1)
      rs2_match(src_idx)(prv_idx) := (has_rr && has_rs2 && match_rs2)
      rs1_match_exists(src_idx) := rs1_match(src_idx).reduceTree(_|_)
      rs2_match_exists(src_idx) := rs2_match(src_idx).reduceTree(_|_)
      rs1_match_idx(src_idx) := ReduceTreePriorityEncoder(rs1_match(src_idx))
      rs2_match_idx(src_idx) := ReduceTreePriorityEncoder(rs2_match(src_idx))
    }
  }
  (0 until p.dec_bw).map(i => {
    io.rs1_provider_idx(i).valid := rs1_match_exists(i)
    io.rs1_provider_idx(i).bits  := rs1_match_idx(i)
    io.rs2_provider_idx(i).valid := rs2_match_exists(i)
    io.rs2_provider_idx(i).bits  := rs2_match_idx(i)
  })
}


/// Register rename unit.
class RegisterRenameUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle { 
    // Input macro-ops
    val mops  = Input(Vec(p.dec_bw, new MacroOp))

    // Output physical register operands
    val pd    = Output(Vec(p.dec_bw, p.Prn()))
    val ps1   = Output(Vec(p.dec_bw, p.Prn()))
    val ps2   = Output(Vec(p.dec_bw, p.Prn()))

    // Physical registers available for allocation
    val frl_alc  = Input(Vec(p.dec_bw, Valid(p.Prn())))

    // Register map read ports
    val map_rp_rs1 = Flipped(Vec(p.dec_bw, new MapReadPort))
    val map_rp_rs2 = Flipped(Vec(p.dec_bw, new MapReadPort))
  })
  val mops = io.mops

  // Resolve "global" dependences with the register map read ports.
  val rs1_valid = mops.map(x => x.src1 === SrcType.S_REG)
  val rs2_valid = mops.map(x => x.src2 === SrcType.S_REG)
  for (idx <- 0 until p.dec_bw) {
    io.map_rp_rs1(idx).areg := Mux(rs1_valid(idx), mops(idx).rs1, 0.U)
    io.map_rp_rs2(idx).areg := Mux(rs2_valid(idx), mops(idx).rs2, 0.U)
  }

  // Resolve the physical destination register names. 
  //
  // Some non-scheduled operations do *not* have a physical destination 
  // (ie. no-op instructions). For squashed 'mov' operations, the physical
  // destination corresponds to the physical register of the source operand
  // that is being moved (so we need to read it from the register map). 
  //
  // FIXME: Here I think we're assuming that results on the register map read
  // ports are always available on the same cycle. I'm not sure this is a 
  // reasonable assumption.
  //
  val pd = Wire(Vec(p.dec_bw, p.Prn()))
  for (idx <- 0 until p.dec_bw) {
    val mop    = mops(idx)
    val is_alc = mop.is_allocation()
    val is_mov = (mop.mov_ctl =/= MovCtl.NONE)

    // The allocated physical destination (if one exists) 
    val allocated_pd = Mux(is_alc, io.frl_alc(idx).bits, 0.U)

    // The moved physical destination (if one exists)
    // FIXME: You're missing moved immediates!
    val moved_pd = MuxCase(0.U, Seq(
      (mop.mov_ctl === MovCtl.SRC1) -> io.map_rp_rs1(idx).preg,
      (mop.mov_ctl === MovCtl.SRC2) -> io.map_rp_rs2(idx).preg,
    ))

    pd(idx) := MuxCase(0.U, Seq(
      (is_mov) -> moved_pd,
      (is_alc) -> allocated_pd,
    ))
  }

  // Compute "local" dependences within the decode window
  val dep = Module(new LocalDependenceUnit)
  dep.io.mops := mops
  val rs1_providers = dep.io.rs1_provider_idx
  val rs2_providers = dep.io.rs2_provider_idx

  // Resolve all of the physical source register names.
  val ps1 = Wire(Vec(p.dec_bw, p.Prn()))
  val ps2 = Wire(Vec(p.dec_bw, p.Prn()))
  for (idx <- 0 until p.dec_bw) {
    val rs1_provider_idx = rs1_providers(idx).bits
    val rs2_provider_idx = rs2_providers(idx).bits
    ps1(idx) := Mux(rs1_providers(idx).valid, 
      pd(rs1_provider_idx),  //FIXME
      io.map_rp_rs1(idx).preg
    )
    ps2(idx) := Mux(rs2_providers(idx).valid, 
      pd(rs2_provider_idx),  //FIXME
      io.map_rp_rs2(idx).preg
    )
  }

  io.pd  := pd
  io.ps1 := ps1
  io.ps2 := ps2

}

