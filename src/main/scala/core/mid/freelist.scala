package zno.core.mid

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._

import zno.common._
import zno.core.uarch._

// Physical register freelist
//
// FIXME: Actually free physical registers, eventually ..
//
class RegisterFreeList(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val alc_en   = Input(Vec(p.dec_win.size, Bool()))
    val alc_idx  = Output(Vec(p.dec_win.size, Valid(p.Prn())))
    val num_free = Output(UInt(log2Ceil(p.prf.size + 1).W))
  })
  val alc_idx = io.alc_idx

  // Defaults
  for (i <- 0 until p.dec_win.size) {
    alc_idx(i).valid := false.B
    alc_idx(i).bits  := 0.U
  }

  // The freelist consists of one bit for each physical register.
  // The first register (physical register 0) is always reserved.
  val frl   = RegInit(UInt(p.prf.size.W), ~(1.U(p.prf.size.W)))
  io.num_free := PopCount(frl)

  // Current set of free registers available to allocate
  val free_preg  = ChainedPriorityEncoderOH(frl, p.dec_win.size)

  // Drive free register indexes on each allocation port.
  // An index is valid only when it is nonzero.
  //
  // NOTE: The available free register indexes are always being driven, 
  // regardless of whether or not the 'valid' bit is high on the port.
  for (i <- 0 until p.dec_win.size) {
    alc_idx(i).valid := free_preg(i).orR
    alc_idx(i).bits  := OHToUInt(free_preg(i))
  }

  // Mask off only the set of bits that were allocated this cycle.
  //
  // The 'en' signal on each port indicates whether or not an entry
  // should be consumed (and masked off starting on the next cycle).
  val free_mask  = (alc_idx zip free_preg).map({
    case (a, f) => f & Fill(p.prf.size, a.valid)
  }).reduce(_|_)

  // Compute the value of the freelist for the next cycle.
  frl := (frl & ~free_mask)
}


