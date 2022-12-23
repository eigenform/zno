package zno.common

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum


// "Producer" (upstream) interface to a [DecouplingFIFO]. 
class FIFOProducerIO[T <: Data](gen: T, width: Int) extends Bundle {
  // An array of data elements to-be-enqueued
  val data = Output(Vec(width, gen))
  // The number of valid data elements in the current transaction
  val len  = Output(UInt(log2Ceil(width+1).W))
  // The maximum number of data elements that can be enqueued
  val lim  = Input(UInt(log2Ceil(width+1).W))
}


// "Consumer" (downstream) interface to a [DecouplingFIFO]. 
class FIFOConsumerIO[T <: Data](gen: T, width: Int) extends Bundle {
  // The oldest set of data elements in the FIFO
  val data = Input(Vec(width, gen))
  // The number of valid data elements in the current transaction
  val len  = Input(UInt(log2Ceil(width+1).W))
  // The number of data elements to be consumed from the FIFO
  val take = Output(UInt(log2Ceil(width+1).W))
}


// A first-in first-out queue with multiple "lanes".
//
// `gen`     - The type of entries stored by this FIFO
// `width`   - The maximum dequeue/enqueue "packet" size (number of entries)
// `entries` - The number of entries stored in the FIFO
//
// A producer sends data via [FIFOProducerIO].
// A consumer receives data via [FIFOConsumerIO].
//
// Output to the consumer always contains the oldest entries in the FIFO 
// if the number of entries is nonzero. The consumer is expected to indicate
// how many entries will be dequeued on the next clock edge.
//
// NOTE: Is there a version of this with bypassing directly from enq.data to 
// deq.data when there are no entries present? (and would you ever want such
// a thing? I haven't thought hard enough about it yet?)
//
// NOTE: Remember that this is *behavioral*. 
// How are these things physically implemented? 
//
class DecouplingFIFO[T <: Data](gen: T, width: Int, entries: Int) 
  extends Module
{
  val enq = IO(Flipped(new FIFOProducerIO(gen, width)))
  val deq = IO(Flipped(new FIFOConsumerIO(gen, width)))

  val used = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val data = Reg(Vec(entries, gen))
  val rptr = RegInit(0.U(log2Ceil(entries).W))
  val wptr = RegInit(0.U(log2Ceil(entries).W))

  // (Outputs to the consumer are initialized to zero here)
  for (idx <- 0 until width) {
    deq.data(idx) := (0.U).asTypeOf(gen)
  }

  // Determine the number of [un]occupied entries in the FIFO.
  // Then, the maximum number of enqueuable/dequeuable entries for this cycle.
  val num_used = PopCount(used)
  val num_free = entries.U - num_used
  val enq_max  = Mux(num_free >= width.U, width.U, num_free)
  val deq_max  = Mux(num_used >= width.U, width.U, num_used)
  enq.lim     := enq_max
  deq.len     := deq_max

  val enq_vld  = (enq.len =/= 0.U)
  val enq_rdy  = (enq.len <= enq_max)
  val deq_vld  = (deq_max =/= 0.U)
  val deq_rdy  = (deq.take <= deq_max) && (deq.take =/= 0.U)

  // Always drive the oldest entries in the FIFO to the consumer when
  // the number of available entries is non-zero.
  when (deq_vld) {
    for (idx <- 0 until width) {
      val cur = rptr + idx.U
      val en  = (deq_max > idx.U)
      when (en) {
        deq.data(idx) := data(cur)
      }
    }
  }

  // Enqueue entries from a producer.
  when (enq_vld && enq_rdy) {
    for (idx <- 0 until width) {
      val cur = wptr + idx.U
      val en  = (enq.len > idx.U)
      when (en) {
        data(cur) := enq.data(idx)
        used(cur) := true.B
      }
    }
    wptr := wptr + enq.len
  }

  // When the consumer is driving 'deq.take', dequeue the requested number
  // of entries (indicating that the current output was consumed).
  when (deq_rdy) {
    for (idx <- 0 until width) {
      val cur = rptr + idx.U
      val en  = (deq.take > idx.U)
      when (en) {
        used(cur) := false.B
      }
    }
    rptr := rptr + deq.take
  }

  printf("free=%d used=%d enq_max=%d deq_max=%d\n", 
    num_free, num_used, enq_max, deq_max)
  printf("enq len=%x lim=%x\n", enq.len, enq.lim)
  printf("deq len=%x take=%x\n", deq.len, deq.take)
}


class Packet[T <: Data](gen: T, width: Int) extends Bundle {
  val data = Output(Vec(width, gen))
  val len  = Output(UInt(log2Ceil(width+1).W))

  def fire: Bool = (this.len > 0.U)
}


