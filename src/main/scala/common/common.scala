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
      res(i) := ReduceTreePriorityEncoder(mask)
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

object ReduceTreePriorityMux {
  def apply[T <: Data](in: Seq[(Bool, T)]): T = {
    ReduceTree(in, (x: (Bool, T), y: (Bool, T)) => {
      val sel_x    = x._1
      val sel_y    = y._1
      val val_x    = x._2
      val val_y    = y._2
      val sel_next = (sel_x || sel_y)
      (sel_next, Mux(sel_x, val_x, val_y))
    })._2
  }
  def apply[T <: Data](sel: Seq[Bool], in: Seq[T]): T = apply(sel zip in)
  def apply[T <: Data](sel: Bits, in: Seq[T]): T = {
    apply((0 until in.size).map(sel(_)), in)
  }
}

object ReduceTreePriorityEncoder {
  def apply(in: Seq[Bool]): UInt = {
    ReduceTreePriorityMux(in, (0 until in.size).map(_.asUInt))
  }
  def apply(in: Bits): UInt = apply(in.asBools)
}



