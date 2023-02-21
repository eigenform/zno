package zno.sim

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._

import chiseltest._
import chiseltest.experimental._

import scala.collection.mutable.HashMap


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


