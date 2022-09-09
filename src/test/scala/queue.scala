
import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.experimental._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._
import org.scalatest.flatspec.AnyFlatSpec

class MultiFIFOSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "MultiFIFO"
  it should "work" in {
    test(new zno.common.MultiFIFO(
      UInt(8.W), enq_width=4, deq_width=4, entries=32
    )) { dut => 
      implicit val clk: Clock = dut.clock

      for (i <- 1 until 11) {
        dut.enq(0).valid.poke(true.B)
        dut.enq(1).valid.poke(true.B)
        dut.enq(2).valid.poke(true.B)
        dut.enq(3).valid.poke(true.B)
        dut.enq(0).bits.poke(i.U)
        dut.enq(1).bits.poke(i.U)
        dut.enq(2).bits.poke(i.U)
        dut.enq(3).bits.poke(i.U)

        dut.deq(0).ready.poke(true.B)
        dut.deq(1).ready.poke(true.B)
        val x = dut.deq(0).bits.peek().litValue
        val y = dut.deq(0).bits.peek().litValue
        println("deq[0] = " + x)
        println("deq[1] = " + y)

        clk.step()

      }


    }
  }
}

class PacketFIFOSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "PacketFIFO"
  it should "work" in {
    test(new zno.common.PacketFIFO(UInt(8.W), width=4, entries=32)) { dut => 
      implicit val clk: Clock = dut.clock

      for (i <- 1 until 9) {

        val lim = dut.enq.limit.peek()
        dut.enq.len.poke(lim)
        if (lim.litValue != 0) {
          dut.enq.valid.poke(true.B)
          for (idx <- 0 until lim.litValue.toInt) {
            dut.enq.data(idx).poke(i.U)
          }
        } else {
          dut.enq.valid.poke(false.B)
        }

        dut.deq.limit.poke(2.U)
        val len = dut.deq.len.peek()
        println("deq len " + len.litValue)
        if (len.litValue != 0) {
          dut.deq.ready.poke(true.B)
          for (idx <- 0 until len.litValue.toInt) {
            println("deq[" + idx + "] = " + dut.deq.data(idx).peek())
          }
        } else {
          dut.deq.ready.poke(false.B)
        }
        clk.step()
      }
    }
  }
}

