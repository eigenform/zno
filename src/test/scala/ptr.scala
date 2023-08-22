
import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.experimental._
import org.scalatest.flatspec.AnyFlatSpec

import zno.common._

class GenericFrlAllocPort(capacity: Int) extends Bundle {
  val en  = Output(Bool())
  val idx = Input(Valid(UInt(log2Ceil(capacity).W)))
}
class GenericFrlKillPort(capacity: Int) extends Bundle {
  val idx = Output(Valid(UInt(log2Ceil(capacity).W)))
}


// State for tracking occupancy in a random-access storage device.
//
// NOTE: Is it unreasonable to assume that we can prepare the 'avail' ports 
// (after the priority encoders!) *and* expect a consumer to sample them and 
// decide whether or not to drive the corresponding 'alc' port in one cycle?
// Might need an additional cycle of delay? 
//
class GenericFreelist(capacity: Int, 
  num_alc_ports: Int,
  num_kill_ports: Int,
  ) extends Module 
{

  val ptr_type = UInt(log2Ceil(capacity).W)

  val io = IO(new Bundle {
    // Requests for entries to be allocated
    val alc      = Input(Vec(num_alc_ports, Bool()))
    // Free entries (if they exist)
    val avail    = Output(Vec(num_alc_ports, Valid(ptr_type)))
    // Requests for entries to be freed
    val kill     = Input(Vec(num_kill_ports, Valid(ptr_type)))
    // The number of free entries
    val num_free = Output(UInt(log2Ceil(capacity + 1).W))
  })

  // Bitvector indicating which entries are free (where '1' means free).
  val frl = RegInit(UInt(capacity.W), ~(1.U(capacity.W)))
  io.num_free := PopCount(frl)

  // Defaults
  for (i <- 0 until num_alc_ports) {
    io.avail(i).valid := false.B
    io.avail(i).bits  := 0.U
  }

  // Select up to 'num_alc_ports' free entries and drive the 'avail' ports
  val avail_oh = ChainedPriorityEncoderOH(frl, num_alc_ports)
  val avail_valid = avail_oh.map({ case (x) => x.orR })

  for (i <- 0 until num_alc_ports) {
    printf("avail_oh=%b\n", avail_oh(i))
    io.avail(i).valid := avail_valid(i)
    io.avail(i).bits  := Mux(avail_valid(i), OHToUInt(avail_oh(i)), 0.U)
  }

  // Bitmask of entries to be invalidated (set) this cycle
  val kill_mask = io.kill.map({
    case (x) => Mux(x.valid, UIntToOH(x.bits), 0.U(capacity.W))
  }).reduce(_|_)
  printf("killmask=%b\n", kill_mask)

  // Which available entries have been consumed?
  val avail_consumed = (io.alc zip avail_valid).map({
    case (allocated, valid) => allocated && valid
  })

  // Bitmask of entries to be allocated (unset) this cycle
  val free_mask  = (avail_consumed zip avail_oh).map({
    case (consumed, oh) => Mux(consumed, oh, 0.U)
  }).reduce(_|_)
  printf("freemask=%b\n", free_mask)

  val next_frl = (frl | kill_mask) & ~free_mask
  printf("next_frl=%b\n", next_frl)

  frl := next_frl

}

class GenericFreelistSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "GenericFreelist"
  it should "work" in {
    test(new GenericFreelist(16, 2, 2)) { dut => 
      implicit val clk: Clock = dut.clock

      for (i <- 0 until 8) {
        println("------------" + "cycle " + i + "-----------------")

        dut.io.alc(0).poke(true.B)
        dut.io.alc(1).poke(false.B)
        dut.io.kill(0).valid.poke(false.B)
        dut.io.kill(1).valid.poke(false.B)
        dut.io.kill(0).bits.poke(0.U)
        dut.io.kill(1).bits.poke(0.U)


        for (j <- 0 until 2) {
          val alc_idx_valid = dut.io.avail(j).valid.peek().litToBoolean
          val alc_idx = dut.io.avail(j).bits.peek()
          println("alc port " + j + ": valid=" + alc_idx_valid + " idx=" + alc_idx)
        }

        clk.step()
      }

    }
  }
}



// Wrapper around state for tracking pointers into unordered (effectively 
// random-access) storage.
//
// 'alc_width' indicates the number of ports for allocating new entries. 
//
class RamPtr(val num_entries: Int, val alc_width: Int) {



  // The type of this pointer.
  def ptr_type(): UInt = UInt(log2Ceil(num_entries).W)

  // Bitvector tracking the position of all free entries
  val frl  = RegInit(UInt(num_entries.W), ~(0.U(num_entries.W)))

  val num_free = PopCount(frl)

  // A set of pointers (in one-hot form) which are available for allocation 
  val next = ChainedPriorityEncoderOH(this.frl, alc_width)

  // Storage is empty when all freebits are set
  val empty = WireInit(this.frl.asUInt.andR)

  // Storage is full when all freebits are unset
  val full  = WireInit(!this.frl.asUInt.orR)


  def get_allocations() = {
    val next = ChainedPriorityEncoderOH(this.frl, alc_width)
    next
  }

}
object RamPtr {
  def apply(num_entries: Int, alc_width: Int): RamPtr = { 
    new RamPtr(num_entries, alc_width) 
  }
}


class AlcPort[T <: Data](data: T, num_entries: Int) extends Bundle {
  val wr_data  = Input(Valid(data))
  val idx      = Output(Valid(UInt(log2Ceil(num_entries).W)))
}

class MyUnorderedQueue extends Module {
  val io = IO(new Bundle {
    val alc = Vec(2, new AlcPort(UInt(32.W), 16))
  })

  val mem = Reg(Vec(16, UInt(32.W)))
  var ptr = RamPtr(16, 2)

  val alcs = ptr.get_allocations()


  for (idx <- 0 until 2) {

    val alc_idx = OHToUInt(alcs(idx))

    printf("ptr.next = %b, %d\n", alcs(idx), alc_idx)

    io.alc(idx).idx.bits  := alc_idx
    io.alc(idx).idx.valid := true.B

    val consumed = (io.alc(idx).idx.fire && io.alc(idx).wr_data.fire)
    when (consumed) {
      mem(alc_idx) := io.alc(idx).wr_data.bits
      ptr.frl := ptr.frl & ~alcs(idx)
    }


  }

  printf("ptr frl=%b\n", ptr.frl)

  //when (io.in.fire) {
  //  mem(ptr.enq) := io.in.bits
  //  ptr.push()
  //}

  //when (io.out.fire) {
  //  io.out.bits := mem(ptr.deq)
  //  ptr.pop()
  //} .otherwise {
  //  io.out.bits  := 0.U
  //}

}

class RamPtrSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "MyUnorderedQueue"
  it should "work" in {
    test(new MyUnorderedQueue) { dut => 
      implicit val clk: Clock = dut.clock

      for (i <- 0 until 16) {
        println("------------" + "cycle " + i + "-----------------")

        dut.io.alc(0).wr_data.valid.poke(true.B)
        dut.io.alc(1).wr_data.valid.poke(false.B)

        for (j <- 0 until 2) {
          val alc_idx_valid = dut.io.alc(j).idx.valid.peek().litToBoolean
          val alc_idx = dut.io.alc(j).idx.bits.peek()
          println("alc port " + j + ": valid=" + alc_idx_valid + " idx=" + alc_idx)
        }

        clk.step()
      }

    }
  }
}


