package zno.common

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

// Return the bit index of the most-significant set bit.
//
// NOTE: [PriorityEncoder] from Chisel3 selects the least-significant bit.
object PriorityEncoderHi {
  def apply(in: Seq[Bool]): UInt = {
    PriorityMux(in, (in.size until 0).map(_.asUInt))
  }
  def apply(in: Bits): UInt = {
    apply(in.asBools.reverse)
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


