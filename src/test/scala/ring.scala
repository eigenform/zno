
import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.experimental._
import org.scalatest.flatspec.AnyFlatSpec
import zno.common._


class MyCircularQueue extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(32.W)))
    val out = Decoupled(UInt(32.W))
  })
  val mem = Reg(Vec(16, UInt(32.W)))
  val ptr = RingPtr(16)

  io.in.ready := !ptr.full
  io.out.valid := !ptr.empty

  printf("ptr deq=%d enq=%d\n", ptr.deq, ptr.enq)

  when (io.in.fire) {
    mem(ptr.enq) := io.in.bits
    ptr.push()
  }

  when (io.out.fire) {
    io.out.bits := mem(ptr.deq)
    ptr.pop()
  } .otherwise {
    io.out.bits  := 0.U
  }

}

class RingPtrSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "MyCircularQueue"
  it should "work" in {
    test(new MyCircularQueue) { dut => 
      implicit val clk: Clock = dut.clock

      for (i <- 0 until 32) {
        println("------------" + "cycle " + i + "-----------------")
        val input_ready = dut.io.in.ready.peek().litToBoolean
        if (input_ready) {
          dut.io.in.valid.poke(true.B)
          dut.io.in.bits.poke(i)
        } else {
          dut.io.in.valid.poke(false.B)
          dut.io.in.bits.poke(0)
        }

        if (i % 4 == 0) {
          dut.io.out.ready.poke(true.B)
          val output_valid = dut.io.out.valid.peek().litToBoolean
          if (output_valid) {
            val output = dut.io.out.bits.peek()
            println("got " + output)
          }
        } else {
          dut.io.out.ready.poke(false.B)
        }
        clk.step()
      }

    }
  }
}


