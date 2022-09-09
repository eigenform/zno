package zno.common

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum

object PacketIOError extends ChiselEnum {
  val OKAY, LIM = Value
}

// A packet with some variable number of entries (bounded by some max width). 
//
// NOTE: We're representing 'width' with 'log2(width+1)' bits
//
class DecoupledPacketIO[T <: Data](gen: T, width: Int) extends Bundle 
{
  // A set of entries contained in a transaction
  val data  = Output(Vec(width, gen))

  // The number of entries in this packet.
  val len   = Output(UInt(log2Ceil(width+1).W))

  // Indicates that all entries in this packet are valid.
  val valid = Output(Bool())

  // Indicates that some consumer is ready to accept a packet.
  val ready = Input(Bool())

  // The maximum number of entries a consumer can accept.
  val limit = Input(UInt(log2Ceil(width+1).W))

  // Indicates when a transaction should occur.
  def fire: Bool = (this.valid && this.ready)
  
  // Drive defaults (producer side)
  def drive_producer_default(): Unit = {
    for (idx <- 0 until width) {
      this.data(idx) := (0.U).asTypeOf(gen)
    }
    this.len   := 0.U
    this.valid := false.B
  }

  // Drive defaults (flipped, consumer side)
  def drive_consumer_default(): Unit = {
    this.ready := false.B
    this.limit := 0.U
  }
}

// A FIFO queue (with the depth given by 'entries') accepting a variable 
// number of input/output elements (up to some maximum given by 'width').
class PacketFIFO[T <: Data](gen: T, width: Int, entries: Int)
  extends Module
{
  val enq = IO(Flipped(new DecoupledPacketIO(gen, width)))
  val deq = IO(new DecoupledPacketIO(gen, width))

  val used = Reg(Vec(entries, Bool()))
  val data = Reg(Vec(entries, gen))
  val rptr = RegInit(0.U(log2Ceil(entries).W))
  val wptr = RegInit(0.U(log2Ceil(entries).W))

  val num_used = PopCount(used)
  val num_free = entries.U - num_used

  enq.drive_consumer_default()
  deq.drive_producer_default()

  // Tell a producer about the maximum number of entries that can be enqueued
  // from a single packet. We're ready to enqueue when we have enough free 
  // entries to handle a packet.
  val max_enq = Mux(num_free >= width.U, width.U, num_free)
  enq.limit  := max_enq
  enq.ready  := (enq.len <= max_enq) && (enq.len =/= 0.U)

  // Enqueue some number of entries
  when (enq.fire) {
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

  // Get the maximum number of entries that can be dequeued, then restrict
  // to the limit imposed by the downstream consumer.
  val max_deq = Mux(num_used >= width.U, width.U, num_used)
  val num_deq = Mux(deq.limit < max_deq, deq.limit, max_deq)
  deq.len    := num_deq
  deq.valid  := (num_deq =/= 0.U)

  // Dequeue some number of entries
  when (deq.fire) {
    for (idx <- 0 until width) {
      val cur = rptr + idx.U
      val en  = (deq.len > idx.U)
      when (en) {
        deq.data(idx) := data(cur)
        used(cur)     := false.B
      }
    }
    rptr := rptr + deq.len
  }

  printf("free=%d used=%d max_enq=%d num_deq=%d\n", num_free, num_used, max_enq, num_deq)
  printf("enq len=%x lim=%x ready=%b valid=%b\n", enq.len, enq.limit, enq.ready, enq.valid)
  printf("deq len=%x lim=%x ready=%b valid=%b\n", deq.len, deq.limit, deq.ready, deq.valid)

}



