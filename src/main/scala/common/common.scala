package zno.common

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

class SimpleCAMReadPort[T <: Data, D <: Data](t: T, d: D) extends Bundle {
  val en   = Input(Bool())
  val tag  = Input(t)
  val data = Output(d)
}
class SimpleCAMWritePort[T <: Data, D <: Data](t: T, d: D) extends Bundle {
  val en   = Input(Bool())
  val tag  = Input(t)
  val data = Input(d)
}

// [Naive] fully-associative content-addressible memory. 
class SimpleCAM[T <: Data, D <: Data]
  (t: T, d: D, size: Int, num_rp: Int, num_wp: Int) extends Module 
{
  val io = IO(new Bundle {
    val rp = Vec(num_rp, new SimpleCAMReadPort(t, d))
    val wp = Vec(num_wp, new SimpleCAMWritePort(t, d))
  })

  val tag  = RegInit(0.U.asTypeOf(Vec(size, t)))
  val data = RegInit(0.U.asTypeOf(Vec(size, d)))

  // FIXME
}


// Return the bit index of the most-significant set bit.
//
// NOTE: [PriorityEncoder] from Chisel3 selects the least-significant bit.
object PriorityEncoderHi {
  def apply(in: Seq[Bool]): UInt = {
    PriorityMux(in, (0 until in.size).reverse.map(_.asUInt))
  }
  def apply(in: Bits): UInt = {
    apply(in.asBools)
  }
}

// Chained priority encoder selecting up to 'width' least-significant bits 
// from the input. 
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


