package zno.common.bitpat

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

// Trait for automatically converting a [[Bundle]] literal into a [[BitPat]].
//
// NOTE: '.to_bitpat()' is only supposed to be used on Bundle literals!
//
trait AsBitPat {
  this: Bundle =>

  // Get the UInt associated with some [[Data]].
  def lit[T <: Data](n: T): UInt   = { n.litValue.U((n.getWidth).W) }

  // Convert some [[Data]] into a [[BitPat]].
  def pat[T <: Data](n: T): BitPat = { BitPat(lit(n)) }

  def to_bitpat(): BitPat = {
    val list: Seq[Data] = this.getElements.reverse
    if (list.length == 1) {
      pat(list(0))
    }
    else if (list.length >= 2) {
      var res = pat(list(0)) ## pat(list(1))
      for (field <- list.drop(2)) { 
        res = res ## pat(field) 
      }
      res
    } else {
      throw new IllegalArgumentException("No elements in this bundle?")
    }
  }
}


