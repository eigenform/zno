
import chisel3._
import chiseltest._
import chiseltest.experimental._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._

import scala.io.Source
import java.io._

import zno.riscv.dbg._

case class TestMemory(val bus: DebugBus)(implicit val clk: Clock) {
  def read_file_bytes(f: String): Array[Byte] = {
    val file = new File(f)
    val in = new FileInputStream(file)
    val bytes = new Array[Byte](file.length.toInt)
    in.read(bytes)
    bytes
  }
  def read_file_data(f: String): Array[UInt] = {
    val bytes = read_file_bytes(f)
    val data  = { 
      for (b <- bytes)
        yield b.U(8.W)
    }
    data
  }

  val mem = read_file_data("rvfw/test.data.bin")
  def run() = {
    val wen   = bus.wen.peek().litToBoolean
    val addr  = (bus.addr.peek().litValue & 0x00ffffffL).toInt
    val mask  = bus.wid.peek().litValue
    if (wen) {
      val wdata = bus.wdata.peek()
      println(f"[TestRAM] store addr=0x${addr}%x res=0x${wdata.litValue}%x")
      for (i <- 0 until 4) {
        if ( (mask & (1 << i)) != 0) {
          val byte = ( (wdata.litValue << (8 * i)) & 0xff ).U(8.W)
          mem(addr + i) = byte
        }
      }
    } else {
      var res: BigInt = 0
      for (i <- 0 until 4) {
        if ( (mask & (1 << i)) != 0) {
          res = res | mem(addr + i).litValue << (8 * i)
        }
      }
      println(f"[TestRAM] load addr=0x${addr}%x res=0x${res}%x")
      bus.data.poke(res)
    }
  }
}

case class TestROM(val bus: DebugBus)(implicit val clk: Clock) {

    // Get a set of 32-bit words from some file 
    val instrs = {
      for (line <- Source.fromFile("rvfw/test.text.mem").getLines().toArray)
        yield BigInt(line, 16).U(32.W)
    }

    def run() = {
      val raddr = bus.addr.peek()
      val rdata = this.instrs((raddr.litValue >> 2).toInt)
      println(f"[TestROM] read addr=0x${raddr.litValue}%x data=0x${rdata.litValue}%x")
      bus.data.poke(rdata)
    }

}



class RvSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Hart"
  it should "work" in {
    test(new zno.riscv.hart.Hart) { dut => 
      implicit val clk: Clock = dut.clock

      val rom = TestROM(dut.ibus)
      val ram = TestMemory(dut.dbus)

      fork {
        var steps = 0
        while (dut.dbg.pc.peek().litValue != 0x68) {
        //while (steps < 24) {
          println("------------------------------------------")
          rom.run()
          ram.run()
          steps += 1
          dut.clock.step()
        }
      }.join()

    }
  }
}

