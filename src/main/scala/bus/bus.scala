package zno.bus

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

object SimpleBusWidth extends ChiselEnum {
  val NULL = Value("b00".U)
  val BYTE = Value("b01".U) // 8-bit transfer
  val HALF = Value("b10".U) // 16-bit transfer
  val WORD = Value("b11".U) // 32-bit transfer
}

object SimpleBusErr extends ChiselEnum {
  val OKAY = Value("b00".U) // Transaction completed successfully
  val AXI  = Value("b01".U) // AXI slv/dec error
  val HUH  = Value("b11".U)
}

class SimpleBusReq extends Bundle {
  val addr  = UInt(32.W)       /// Address
  val data  = UInt(32.W)       /// Write data
  val wid   = SimpleBusWidth() /// Requested memory access width
  val wen   = Bool()           /// Write enable
}
class SimpleBusResp extends Bundle {
  val data  = UInt(32.W)
  val err   = SimpleBusErr()
}

// Interface for sending transactions
class SimpleBusSource extends Bundle {
  val req  = Decoupled(new SimpleBusReq)
  val resp = Flipped(Decoupled(new SimpleBusResp))
}

// Interface for servicing transactions
class SimpleBusSink extends Bundle {
  val req  = Flipped(Decoupled(new SimpleBusReq))
  val resp = Decoupled(new SimpleBusResp)
}


