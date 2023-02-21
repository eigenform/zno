import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.experimental._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.HashMap

import chiseltest.simulator.WriteVcdAnnotation

import zno.core.front._
import zno.core.uarch._
import zno.common._

// Simple container for RISC-V stimulus (this sucks, lol)
class SimROM(file: String) {
  import java.nio.file.{Files, Paths}

  val data = Files.readAllBytes(Paths.get(file))
  if (data.length % 4 != 0) {
    throw new Exception("ROM length must be a multiple of 4")
  }

  def read32(addr: BigInt): BigInt = {
    val off = (addr & 0x00ffffffL).toInt
    if (off % 4 != 0) {
      throw new Exception(f"read32 address $addr%x must be 32-bit aligned")
    }
    if ((off + 4) >= data.length) {
      throw new Exception(f"Can't read $addr%x, length=${data.length}%x")
    }
    val word = (
      (data(off + 0) & 0xff) << 0 |
      (data(off + 1) & 0xff) << 8 |
      (data(off + 2) & 0xff) << 16  |
      (data(off + 3) & 0xff) << 24
    ) & 0xffffffffL
    word
  }

  println(f"${read32(0)}%x")
}


// Primitive solution for managing synchronous state outside of the DUT.
// This sucks, but whatever. 
//
// Basically, we're just tracking clock cycles here and updating these 
// maps when we step the DUT clock. 
//
// (If only chiseltest let you instantiate hardware outside the DUT ..)
//
class SimHarness(implicit val clk: Clock) {
  var cycle: BigInt = 0

  var int_state = new HashMap[String, BigInt]()
  var bool_state = new HashMap[String, Boolean]()

  var int_state_pending = new HashMap[String, BigInt]()
  var bool_state_pending = new HashMap[String, Boolean]()

  // Initialize a register
  def init_reg(k: String, v: BigInt)  = { int_state  += (k -> v) }
  def init_reg(k: String, v: Boolean) = { bool_state += (k -> v) }

  // Indicate that some value should be registered on the next clock edge
  def write_reg(k: String, v: BigInt)  = { int_state_pending += (k -> v)  }
  def write_reg(k: String, v: Boolean) = { bool_state_pending += (k -> v) }

  // NOTE: Unfortunate that we need one for each datatype ..
  def read_reg_bool(k: String): Boolean = { bool_state(k) }
  def read_reg_int(k: String): BigInt   = { int_state(k)  }

  def print_state(): Unit = {
    println("=====================================")
    println("SimHarness state (cycle " + this.cycle + ")")
    println("")
    this.int_state.foreach { 
      case (k,v) => println("int :" + k + f" -> $v%x")
    }
    this.bool_state.foreach { 
      case (k,v) => println("bool: " + k + " -> " + v) 
    }
    println("=====================================")
  }

  def step(x: Int = 1) = {
    int_state_pending.foreach  { case (k, v) => int_state(k) = v  }
    bool_state_pending.foreach { case (k, v) => bool_state(k) = v }
    int_state_pending.clear()
    bool_state_pending.clear()
    clk.step(x)
    cycle += x
  }
}

class ZnoFrontcoreSpec extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = ZnoParam()

  behavior of "ZnoFrontcore"
  it should "work" in {
    test(new ZnoFrontcore).withAnnotations(Seq(WriteVcdAnnotation)) { 
      dut => 
      implicit val clk: Clock = dut.clock
      var h = new SimHarness
      var rom = new SimROM("rvfw/test.text.bin")

      def sim_ftq(): Unit = {
        if (h.cycle == 0) {
          dut.io.ftq_in.valid.poke(true.B)
          dut.io.ftq_in.bits.addr.poke(0x80000000L.U)
        } else {
          dut.io.ftq_in.valid.poke(false.B)
          dut.io.ftq_in.bits.addr.poke(0.U)
        }
      }

      h.init_reg("go", false)
      h.init_reg("addr", 0)
      h.init_reg("cyc", 0)
      def sim_ibus(): Unit = {
        // defaults
        dut.io.ibus.resp.valid.poke(false.B)
        dut.io.ibus.resp.bits.addr.poke(0)
        dut.io.ibus.resp.bits.data(0).poke(0)
        dut.io.ibus.resp.bits.data(1).poke(0)
        dut.io.ibus.resp.bits.data(2).poke(0)
        dut.io.ibus.resp.bits.data(3).poke(0)
        dut.io.ibus.resp.bits.data(4).poke(0)
        dut.io.ibus.resp.bits.data(5).poke(0)
        dut.io.ibus.resp.bits.data(6).poke(0)
        dut.io.ibus.resp.bits.data(7).poke(0)

        val req_vld    = dut.io.ibus.req.valid.peekBoolean()
        val req_addr   = dut.io.ibus.req.bits.peekInt()
        val resp_ready = dut.io.ibus.resp.ready.peekBoolean()
        val req_rdy = h.read_reg_bool("go")
        dut.io.ibus.req.ready.poke(!req_rdy)
        if (req_vld && !req_rdy) {
          h.write_reg("go", true)
          h.write_reg("addr", req_addr)
          h.write_reg("cyc", h.cycle)
        }
        if (h.read_reg_bool("go")) {
          if (h.cycle >= h.read_reg_int("cyc") + 4) {
            println("bus transaction driving results ...")
            dut.io.ibus.resp.valid.poke(true.B)
            val addr = h.read_reg_int("addr")
            dut.io.ibus.resp.bits.addr.poke(addr)
            dut.io.ibus.resp.bits.data(0).poke(rom.read32(addr+0x00))
            dut.io.ibus.resp.bits.data(1).poke(rom.read32(addr+0x04))
            dut.io.ibus.resp.bits.data(2).poke(rom.read32(addr+0x08))
            dut.io.ibus.resp.bits.data(3).poke(rom.read32(addr+0x0c))
            dut.io.ibus.resp.bits.data(4).poke(rom.read32(addr+0x10))
            dut.io.ibus.resp.bits.data(5).poke(rom.read32(addr+0x14))
            dut.io.ibus.resp.bits.data(6).poke(rom.read32(addr+0x18))
            dut.io.ibus.resp.bits.data(7).poke(rom.read32(addr+0x1c))

            // When DUT drives ready we assume the results are captured
            if (resp_ready) {
              println("bus transaction done")
              h.write_reg("go", false)
              h.write_reg("addr", 0)
              h.write_reg("cyc", 0)
            }
          } else {
            println("bus transaction in progress..")
          }
        }
      }


      for (_ <- 1 to 16) {
        h.print_state()
        sim_ftq()
        sim_ibus()
        h.step()
      }


      //var cyc = 0
      //while (cyc < 16) {
      //  clk.step()
      //  cyc += 1
      //}
    }
  }

}


