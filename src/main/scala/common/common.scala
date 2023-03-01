package zno.common

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

// Sign-extend some value 'data' to some number of bits 'len'
object Sext {
  def apply(data: UInt, len: Int): UInt = {
    require(len > data.getWidth)
    val sign = data(data.getWidth - 1)
    Cat(Fill(len - data.getWidth, sign), data)
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

// Create a balanced tree of binary operations on some data. 
//
// NOTE: Chisel lets you do this on [Vec], but it seems like it'd be nice to 
// have this on some arbitrary [Seq] of signals?
//
// Is this something that we'd expect synthesis tools to recognize for us (ie. 
// turning things into trees like this), or do we always need to explicitly 
// specify when this should happen?
//
object ReduceTree {
  import java.lang.Math.{floor, log10, pow}

  def apply[T](s: Seq[T], op: (T, T) => T): T = {
    apply(s, op, lop = (x: T) => (x))
  }
  def apply[T](s: Seq[T], op: (T, T) => T, lop: (T) => T): T = {
    require(s.nonEmpty, "Cannot apply reduction on an empty Seq")
    val n = s.length
    n match {
      case 1 => lop(s(0))
      case 2 => op(s(0), s(1))
      case _ => 
        val m = pow(2, floor(log10(n-1) / log10(2))).toInt
        val p = 2 * m - n
        val l = s.take(p).map(lop)
        val r = s.drop(p).grouped(2).map {
          case Seq(a, b) => op(a, b)
        }.toSeq
        apply(l ++ r, op, lop)
    }
  }
}

