package zno.core.mid.dispatch

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._

import zno.common._
import zno.core.uarch._

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

// Register freelist allocation port.
class RflAllocPort(implicit p: ZnoParam) extends Bundle {
  val en = Input(Bool())
  val idx = Valid(UInt(p.pwidth.W))
}

// Register freelist release port.
class RflFreePort(implicit p: ZnoParam) extends Bundle {
  val idx = UInt(p.pwidth.W)
}


// Logic wrapping a list of free physical registers.
//
// FIXME: You're never releasing any physical registers.
// There's no interface for releasing physical registers. 
//
class RegisterFreeList(implicit p: ZnoParam)
  extends Module 
{
  val alc      = IO(Vec(p.id_width, new RflAllocPort))
  val num_free = IO(Output(UInt(log2Ceil(p.num_preg+1).W)))

  // The freelist consists of one bit for each physical register.
  // The first register (physical register 0) is always reserved.
  val frl   = RegInit(UInt(p.num_preg.W), ~(1.U(p.num_preg.W)))

  num_free := PopCount(frl)

  // Defaults
  for (i <- 0 until p.id_width) {
    alc(i).idx.valid := false.B
    alc(i).idx.bits  := 0.U
  }

  // Determine the current set of free registers available to allocate.
  val free_preg  = ChainedPriorityEncoderOH(frl, p.id_width)

  // Drive free register indexes on each allocation port.
  // An index is valid only when it is nonzero.
  //
  // NOTE: The available free register indexes are always being driven, 
  // regardless of whether or not 'en' is high on the corresponding port.
  for (i <- 0 until p.id_width) {
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
  for (i <- 0 until p.id_width) {
    printf("[Freelist]: alc[%x] valid=%b idx=%x\n", i.U,
      alc(i).idx.valid,
      alc(i).idx.bits,
    )
  }
}


