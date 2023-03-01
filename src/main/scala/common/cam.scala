package zno.common

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR

class SimpleCAMReadPort[T <: Data, D <: Data](t: T, d: D) extends Bundle {
  val en   = Input(Bool())
  val tag  = Input(t)
  val data = Output(Valid(d))
}

object CAMWriteCmd extends ChiselEnum {
  val UPDATE     = Value
  val INVALIDATE = Value
}

class SimpleCAMWritePort[T <: Data, D <: Data](t: T, d: D) extends Bundle {
  val en   = Input(Bool())
  val cmd  = Input(CAMWriteCmd())
  val tag  = Input(t)
  val data = Input(d)
}



class SimpleCAM[T <: Data, D <: Data](tgen: T, dgen: D, 
  num_entries: Int, num_rp: Int, num_wp: Int = 1,
) extends Module 
{
  require(num_wp == 1, "Only 1 write port is supported")

  val num_entry_bits = log2Ceil(num_entries)
  val num_tag_bits   = log2Ceil(tgen.getWidth)
  val full_assoc     = (num_entry_bits >= num_tag_bits)

  val io = IO(new Bundle {
    val rp = Vec(num_rp, new SimpleCAMReadPort(tgen, dgen))
    val wp = Vec(num_wp, new SimpleCAMWritePort(tgen, dgen))
  })
  val rp = io.rp
  val wp = io.wp

  // FIXME: Parameterizable memory behavior?
  val r_data  = Mem(num_entries, dgen)
  val r_tags  = Mem(num_entries, tgen)
  val r_valid = RegInit(VecInit(Seq.fill(num_entries)(false.B)))

  // The index of the next free entry (if it exists)
  val free_idx = PriorityEncoder(r_valid.map(!_))

  // These are unused when this CAM is fully associative.
  // When this *isn't* fully associative, updates may miss even 
  // when storage is full (so we need a replacement policy).
  val full     = r_valid.reduceTree(_&_)
  val repl_idx = LFSR(log2Ceil(num_entries))

  // Write ports
  // For fully-associative storage, we either update or insert. 
  // Writes either goto the hit target, or to the next free entry. 
  // Otherwise, the total set of tags may not fit into storage, and we 
  // need to pick an entry to replace when storage is full.

  for (widx <- 0 until num_wp) {
    val hit_arr  = (0 until num_entries).map(i => 
      Mux(r_valid(i), (r_tags(i) === wp(widx).tag), false.B)
    )
    val hit     = VecInit(hit_arr).reduceTree(_|_)
    val hit_idx = Mux(hit, OHToUInt(hit_arr), 0.U)
    val tgt_idx = if (full_assoc) { 
      Mux(hit, hit_idx, free_idx) 
    } else { 
      Mux(hit, hit_idx, Mux(full, repl_idx, free_idx)) 
    }
    when (wp(widx).en) {
      switch (wp(widx).cmd) {
        is (CAMWriteCmd.UPDATE) {
          r_data(tgt_idx)    := wp(widx).data
          when (!hit) {
            r_tags(tgt_idx)  := wp(widx).tag
            r_valid(tgt_idx) := true.B
          }
        }
        is (CAMWriteCmd.INVALIDATE) {
          when (hit) {
            r_tags(tgt_idx)  := 0.U.asTypeOf(tgen)
            r_data(tgt_idx)  := 0.U.asTypeOf(dgen)
            r_valid(tgt_idx) := false.B
          }
        }
      }
    }
  }

  // Read ports
  for (ridx <- 0 until num_rp) {
    val hit_arr  = (0 until num_entries).map(i => 
        Mux(r_valid(i), (rp(ridx).tag === r_tags(i)), false.B)
    )
    val hit      = VecInit(hit_arr).reduceTree(_|_)
    val hit_idx  = Mux(hit, OHToUInt(hit_arr), 0.U)
    val hit_data = Mux(hit, r_data(hit_idx), 0.U.asTypeOf(dgen))
    rp(ridx).data.valid := Mux(rp(ridx).en, hit, false.B)
    rp(ridx).data.bits  := Mux(rp(ridx).en, hit_data, 0.U.asTypeOf(dgen))
  }


}


