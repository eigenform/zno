package zno.common

import chisel3._
import chisel3.util._

// Wrapper around state for managing pointers into a circular FIFO queue.
class RingPtr(val num_entries: Int) {

  // Pointer to the newest element in the queue
  val enq  = Reg(UInt(log2Ceil(num_entries).W))

  // Pointer to the oldest element in the queue
  val deq  = Reg(UInt(log2Ceil(num_entries).W))

  // Bitvector tracking the position of 'valid' allocated entries
  val vld  = Reg(Vec(num_entries, Bool()))

  // The queue is empty when all valid bits are unset
  val empty = WireInit(!this.vld.asUInt.orR)

  // The queue is full when all valid bits are set
  val full  = WireInit(this.vld.asUInt.andR)


  def push(): Unit = { 
    assert(!this.full)
    this.vld(this.enq) := true.B
    this.enq := this.enq + 1.U 
  }

  def pop(): Unit = { 
    assert(!this.empty)
    this.vld(this.deq) := false.B
    this.deq := this.deq + 1.U 
  }

  def clear(): Unit = {
    this.enq := 0.U
    this.deq := 0.U
    this.vld := 0.U.asTypeOf(Vec(num_entries, Bool()))
  }
}

object RingPtr {
  def apply(num_entries: Int): RingPtr = { new RingPtr(num_entries) }
}



