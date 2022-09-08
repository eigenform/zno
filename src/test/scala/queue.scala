
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
        clk.step()
      }

    }
  }
}

