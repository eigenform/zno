
import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.experimental._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._
import org.scalatest.flatspec.AnyFlatSpec

class DecouplingFIFOSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "DecouplingFIFO"
  it should "work" in {
    test(new zno.common.DecouplingFIFO(UInt(8.W), width=4, entries=32)) { dut => 
      implicit val clk: Clock = dut.clock
      for (i <- 1 until 9) {

        val lim = dut.enq.lim.peek()
        dut.enq.len.poke(lim)
        if (lim.litValue != 0) {
          for (idx <- 0 until lim.litValue.toInt) {
            dut.enq.data(idx).poke(i.U)
          }
        } 

        val len = dut.deq.len.peek()
        if (len.litValue != 0) {
          for (idx <- 0 until len.litValue.toInt) {
            println("deq[" + idx + "] = " + dut.deq.data(idx).peek())
          }
          dut.deq.take.poke(1.U)
        } 

        clk.step()

      }
    }
  }
}

