package zno.common

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum

// A FIFO that selects a variable number of entries to enqueue/dequeue. 
//
// FIXME: We assume that used "lanes" are all packed together sequentially
// starting at index 0, and make no attempt to handle "holes".
// For example, behavior is undefined in the following cases where an upstream 
// client presents the following signals intending to use 3 out of 4 lanes:
//
//  enq[0].valid=0 enq[1].valid=1 enq[2].valid=1 enq[3].valid=1
//  enq[0].valid=1 enq[1].valid=0 enq[2].valid=1 enq[3].valid=1
/// enq[0].valid=1 enq[1].valid=1 enq[2].valid=0 enq[3].valid=1
//
class MultiFIFO[T <: Data](
  gen: T, 
  enq_width: Int,
  deq_width: Int,
  entries: Int,
) extends Module 
{

  val enq  = IO(Flipped(Vec(enq_width, Decoupled(gen))))
  val deq  = IO(Vec(deq_width, Decoupled(gen)))
  val free = IO(Output(UInt(log2Ceil(entries+1).W)))
  val used = IO(Output(UInt(log2Ceil(entries+1).W)))

  val data_valid = Reg(Vec(entries, Bool()))
  val data = Reg(Vec(entries, gen))
  val rptr = RegInit(0.U(log2Ceil(entries).W))
  val wptr = RegInit(0.U(log2Ceil(entries).W))

  val num_used = PopCount(data_valid)
  val num_free = entries.U - num_used
  val empty    = (num_used === 0.U)
  val full     = (num_used === entries.U)

  // The upstream client drives some number of 'valid' signals on the 'enq' 
  // lanes indicating how many elements are requested for enqueue
  val enq_valid   = enq.map(e => e.valid)
  val num_req_enq = PopCount(enq_valid)

  // The downstream client drives some number of 'ready' signals on the 'deq' 
  // lanes indicating how many elements are requested for dequeue
  val deq_ready   = deq.map(d => d.ready)
  val num_req_deq = PopCount(deq_ready)

  // The maximum number of entries that can be enqueued or dequeued for
  // this cycle depends on how many free/used entries are available
  val num_max_enq = Mux(num_free >= enq_width.U, enq_width.U, num_free)
  val num_max_deq = Mux(num_used >= deq_width.U, deq_width.U, num_used)

  // Drive ready/valid over all ports depending on the number of entries
  // that are available for enqueue/dequeue
  enq.zipWithIndex foreach {
    case (e, idx) => e.ready := (num_max_enq > idx.U)
  }
  deq.zipWithIndex foreach {
    case (d, idx) => d.valid := (num_max_deq > idx.U)
  }

  // The number of enqueue/dequeue lanes that are firing this cycle
  val num_enq_fire = PopCount(enq.map(e => e.fire))
  val num_deq_fire = PopCount(deq.map(d => d.fire))

  // Drive default output
  for (i <- 0 until deq_width) {
    deq(i).bits  := (0.U).asTypeOf(gen)
  }

  // FIXME: Need to also validate that the number of active enq/deq lanes
  // (num_req_enq/num_req_deq) against (num_max_enq/num_max_deq)?

  // Handle all of the active enqueue lanes
  for (i <- 0 until enq_width) {
    when (enq(i).fire) {
      val cursor          = wptr + i.U
      data(cursor)       := enq(i).bits
      data_valid(cursor) := true.B
    }
  }
  // Handle all of the active dequeue lanes
  for (i <- 0 until deq_width) {
    when (deq(i).fire) {
      val cursor          = rptr + i.U
      deq(i).bits        := data(cursor)
      data_valid(cursor) := false.B
    }
  }

  wptr := wptr + num_enq_fire
  rptr := rptr + num_deq_fire
  free := num_free
  used := num_used

  printf("free=%d max_enq=%d max_deq=%d\n", free, num_max_enq, num_max_deq)
  for (i <- 0 until enq_width) {
    printf("enq[%x] ready=%b, valid=%b\n", i.U, enq(i).ready, enq(i).valid)
  }
  for (i <- 0 until deq_width) {
    printf("deq[%x] valid=%b, ready=%b\n", i.U, deq(i).valid, deq(i).ready)
  }
}


