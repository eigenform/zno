package zno.core.front.bpred

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._
import zno.core.front._

// A bit vector representing some history of two-valued events.
// in this context, these are previously-observed branch directions
// (either "taken" or "not-taken").
class HistoryVector(depth: Int) extends Bundle {
  val bits = UInt(depth.W)

  // Shift in an observation, returning the new value
  def update(taken: Bool): UInt = Cat(this.bits(depth-1, 1), taken)
  // Return the most-recent observed direction
  def recent(): Bool = this.bits(0)
}
object HistoryVector {
  def apply(depth: Int): HistoryVector = {
    (new HistoryVector(depth)).Lit(_.bits -> 0.U)
  }
}

// Representing branch bias in a certain direction ("taken" or "not-taken").
//
// You're intended to use this as a kind of saturating counter, where: 
//
//  - The all-zero bitvector corresponds to a "strongly not-taken" bias
//  - The all-ones bitvector corresponds to a "strongly taken" bias
//
// Note that the high-order bit indicates the actual predicted direction,
// and all lower bits are for hysteresis. For example, the default 2-bit 
// counter represents the following:
//
//  - 11 is "strongly taken"
//  - 10 is "weakly taken"
//  - 01 is "weakly not-taken"
//  - 00 is "strongly not-taken"
//
class BiasVector(width: Int = 2) extends Bundle { 
  require(width >= 2, "Bias vectors must be at least two bits")
  val max_val: Int = (1 << width) - 1
  val bits = UInt(width.W)

  // Sample the predicted direction bit
  def prediction(): Bool = this.bits(width - 1)

  // Produce the next value. 
  def update(taken: Bool): UInt = {
    Mux(taken, 
      Mux(this.bits =/= max_val.U, this.bits + 1.U, this.bits),
      Mux(this.bits =/= 0.U, this.bits - 1.U, this.bits)
    )
  }
}
object BiasVector {
  // Create a new [BiasVector] initialized to some value.
  // This defaults to the "weakly taken" state. 
  def apply(width: Int = 2, default_bias: UInt = "b10".U) = {
    require(default_bias.getWidth == width, 
      f"Incorrect width for default bias; should be $width"
    )
    (new BiasVector(width)).Lit(_.bits -> default_bias)
  }
}


// 'gshare' predictor implementation.
class GsharePredictor(
  val num_entries: Int,
  val ghist_depth: Int,
)(implicit p: ZnoParam) extends Module 
{
  val io = IO(new Bundle {
    val in_ghist = Input(new HistoryVector(ghist_depth))
    val in_pc    = Input(p.ProgramCounter())
  })

  val tagwidth: Int = log2Ceil(num_entries)
  val r_bias  = RegInit(Vec(num_entries, BiasVector()))
}


// Top-level branch prediction unit. 
// o
class BranchPredictionUnit(
  // Depth of the global history register
  val ghist_depth: Int = 12,
)(implicit p: ZnoParam) extends Module 
{
  val ghr = RegInit(HistoryVector(ghist_depth))
}



