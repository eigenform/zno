package zno.amba.axi

import chisel3._
import chisel3.util._
import chisel3.util.experimental.{forceName}
import chisel3.experimental.ChiselEnum

object AXIBurstType extends ChiselEnum {
  val FIXED  = Value("b00".U)
  val INCR   = Value("b01".U)
  val WRAP   = Value("b10".U)
  val RSRV   = Value("b11".U)
}
object AXIRespType extends ChiselEnum {
  val OKAY   = Value("b00".U)
  val EXOKAY = Value("b01".U)
  val SLVERR = Value("b10".U)
  val DECERR = Value("b11".U)
}

class AXIBundle(
  addr_width: Int, 
  data_width: Int, 
  id_width: Int = 6
) extends Bundle {

  // Address channel
  class AddrChannel(addr_width: Int, id_width: Int = 6) extends Bundle {
    val addr  = UInt(addr_width.W)
    val size  = UInt(3.W)
    val len   = UInt(8.W)
    val burst = AXIBurstType()
    val id    = UInt(id_width.W)
    val lock  = Bool()
    val cache = UInt(4.W)
    val prot  = UInt(3.W)
    val qos   = UInt(4.W)
  }
  class WriteDataChannel(data_width: Int) extends Bundle {
    val data = UInt(data_width.W)
    val strb = UInt((data_width/8).W)
    val last = Bool()
  }
  class WriteRespChannel(id_width: Int = 6) extends Bundle {
    val id   = UInt(id_width.W)
    val resp = AXIRespType()
  }
  class ReadDataChannel(data_width: Int, id_width: Int = 6) extends Bundle {
    val data = UInt(data_width.W)
    val id   = UInt(id_width.W)
    val last = Bool()
    val resp = AXIRespType()
  }
  val waddr = Decoupled(new AddrChannel(addr_width))
  val wdata = Decoupled(new WriteDataChannel(data_width))
  val wresp = Flipped(Decoupled(new WriteRespChannel()))
  val raddr = Decoupled(new AddrChannel(addr_width))
  val rdata = Flipped(Decoupled(new ReadDataChannel(data_width)))
}

// Call these to create a new AXI port. 
object AXIPort {
  def source(addr_width: Int, data_width: Int): AXIBundle = {
    new AXIBundle(addr_width, data_width)
  }
  def sink(addr_width: Int, data_width: Int): AXIBundle = {
    Flipped(new AXIBundle(addr_width, data_width))
  }
}

// I'm kind of relying on Vivado (grahhgrg) to integrate a design right now.
// It's nice to define a bundle that Vivado will automatically recognize as 
// an AXI interface.
class AXIExternalPort(
  addr_width: Int, 
  data_width: Int,
  id_width: Int = 6,
) extends Bundle {
  val AWVALID = Output(Bool())
  val AWREADY = Input(Bool())
  val AWID    = Output(UInt(id_width.W))
  val AWADDR  = Output(UInt(addr_width.W))
  val AWLEN   = Output(UInt(8.W))
  val AWSIZE  = Output(UInt(3.W))
  val AWBURST = Output(AXIBurstType())
  val AWLOCK  = Output(Bool())
  val AWCACHE = Output(UInt(4.W))
  val AWPROT  = Output(UInt(3.W))
  val AWQOS   = Output(UInt(4.W))

  val WVALID  = Output(Bool())
  val WREADY  = Input(Bool())
  val WDATA   = Output(UInt(data_width.W))
  val WLAST   = Output(Bool())
  val WSTRB   = Output(UInt((data_width / 8).W))

  val BVALID  = Input(Bool())
  val BREADY  = Output(Bool())
  val BID     = Input(UInt(id_width.W))
  val BRESP   = Input(AXIRespType())

  val ARVALID = Output(Bool())
  val ARREADY = Input(Bool())
  val ARID    = Output(UInt(id_width.W))
  val ARADDR  = Output(UInt(addr_width.W))
  val ARLEN   = Output(UInt(8.W))
  val ARSIZE  = Output(UInt(3.W))
  val ARBURST = Output(AXIBurstType())
  val ARLOCK  = Output(Bool())
  val ARCACHE = Output(UInt(4.W))
  val ARPROT  = Output(UInt(3.W))
  val ARQOS   = Output(UInt(4.W))

  val RVALID  = Input(Bool())
  val RREADY  = Output(Bool())
  val RDATA   = Input(UInt(data_width.W))
  val RRESP   = Input(AXIRespType())
  val RID     = Input(UInt(id_width.W))
  val RLAST   = Input(Bool())

  def connect_sink(wire: AXIBundle): Unit = {
    this.AWVALID          := wire.waddr.valid
    wire.waddr.ready      := this.AWREADY
    this.AWID             := wire.waddr.bits.id
    this.AWADDR           := wire.waddr.bits.addr
    this.AWLEN            := wire.waddr.bits.len
    this.AWSIZE           := wire.waddr.bits.size
    this.AWBURST          := wire.waddr.bits.burst
    this.AWLOCK           := wire.waddr.bits.lock
    this.AWCACHE          := wire.waddr.bits.cache
    this.AWPROT           := wire.waddr.bits.prot
    this.AWQOS            := wire.waddr.bits.qos

    this.WVALID           := wire.wdata.valid
    wire.wdata.ready      := this.WREADY
    this.WDATA            := wire.wdata.bits.data
    this.WLAST            := wire.wdata.bits.last
    this.WSTRB            := wire.wdata.bits.strb

    wire.wresp.valid      := this.BVALID
    this.BREADY           := wire.wresp.ready
    wire.wresp.bits.id    := this.BID
    wire.wresp.bits.resp  := this.BRESP

    this.ARVALID          := wire.raddr.valid
    wire.raddr.ready      := this.ARREADY
    this.ARID             := wire.raddr.bits.id
    this.ARADDR           := wire.raddr.bits.addr
    this.ARLEN            := wire.raddr.bits.len
    this.ARSIZE           := wire.raddr.bits.size
    this.ARBURST          := wire.raddr.bits.burst
    this.ARLOCK           := wire.raddr.bits.lock
    this.ARCACHE          := wire.raddr.bits.cache
    this.ARPROT           := wire.raddr.bits.prot
    this.ARQOS            := wire.raddr.bits.qos

    wire.rdata.valid      := this.RVALID
    this.RREADY           := wire.rdata.ready
    wire.rdata.bits.data  := this.RDATA
    wire.rdata.bits.resp  := this.RRESP
    wire.rdata.bits.id    := this.RID
    wire.rdata.bits.last  := this.RLAST
  }

  def connect_source(wire: AXIBundle): Unit = {
    wire.waddr.valid        := this.AWVALID
    this.AWREADY            := wire.waddr.ready
    wire.waddr.bits.id      := this.AWID
    wire.waddr.bits.addr    := this.AWADDR
    wire.waddr.bits.len     := this.AWLEN
    wire.waddr.bits.size    := this.AWSIZE
    wire.waddr.bits.burst   := this.AWBURST
    wire.waddr.bits.lock    := this.AWLOCK
    wire.waddr.bits.cache   := this.AWCACHE
    wire.waddr.bits.prot    := this.AWPROT
    wire.waddr.bits.qos     := this.AWQOS
                          
    wire.wdata.valid        := this.WVALID
    this.WREADY             := wire.wdata.ready
    wire.wdata.bits.data    := this.WDATA
    wire.wdata.bits.last    := this.WLAST
    wire.wdata.bits.strb    := this.WSTRB
                          
    this.BVALID             := wire.wresp.valid
    wire.wresp.ready        := this.BREADY
    this.BID                := wire.wresp.bits.id
    this.BRESP              := wire.wresp.bits.resp
                          
    wire.raddr.valid        := this.ARVALID
    this.ARREADY            := wire.raddr.ready
    wire.raddr.bits.id      := this.ARID
    wire.raddr.bits.addr    := this.ARADDR
    wire.raddr.bits.len     := this.ARLEN
    wire.raddr.bits.size    := this.ARSIZE
    wire.raddr.bits.burst   := this.ARBURST
    wire.raddr.bits.lock    := this.ARLOCK
    wire.raddr.bits.cache   := this.ARCACHE
    wire.raddr.bits.prot    := this.ARPROT
    wire.raddr.bits.qos     := this.ARQOS
                          
    this.RVALID             := wire.rdata.valid
    wire.rdata.ready        := this.RREADY
    this.RDATA              := wire.rdata.bits.data
    this.RRESP              := wire.rdata.bits.resp
    this.RID                := wire.rdata.bits.id
    this.RLAST              := wire.rdata.bits.last
  }
}

/* I think this is kind of what I want the top-level to look like:
 *
 *  - AXIExternalPort is exposed at the top level
 *  - AXIBundle used internally to move signals around
 *
 */

class TestAXISourceTop(addr_width: Int, data_width: Int) extends Module {
  val axi0_out = IO(new AXIExternalPort(addr_width, data_width))
    .suggestName("M_AXI0")

  val axi = IO(AXIPort.sink(addr_width, data_width))
  axi0_out.connect_sink(axi)
}
class TestAXISinkTop(addr_width: Int, data_width: Int) extends Module {
  val axi0_in = IO(Flipped(new AXIExternalPort(addr_width, data_width)))
    .suggestName("S_AXI0")

  //val axi = IO(AXIPort.source(addr_width, data_width))
  val axi = Wire(AXIPort.source(addr_width, data_width))
  axi0_in.connect_source(axi)
  axi := DontCare
}




class TestAXISinkMemory extends Module {
  val io = IO(new Bundle {
    val axi = AXIPort.sink(4, 32)
  })
  io.axi := DontCare

  object TransferState extends ChiselEnum {
    val IDLE, RESP = Value
  }
  val mem = VecInit(Seq(
    0x00000000.U, 0x10000000.U, 0x20000000.U, 0x30000000.U,
    0x40000000.U, 0x50000000.U, 0x60000000.U, 0x70000000.U,
    0x80000000.U, 0x90000000.U, 0xa0000000.U, 0xb0000000.U,
    0xc0000000.U, 0xd0000000.U, 0xe0000000.U, 0xf0000000.U,
  ))

  val state  = RegInit(TransferState.IDLE)

  // Ready to receive an address
  io.axi.raddr.ready := (state === TransferState.IDLE)
  // We sent a valid response
  io.axi.rdata.valid := (state === TransferState.RESP)


  val data = RegInit(0.U(32.W))

  switch (state) {
    is (TransferState.IDLE) {
      // Source presented us with an address
      when (io.axi.raddr.fire) {
        data  := mem(io.axi.raddr.bits.addr)
        state := TransferState.RESP
      }
    }
    is (TransferState.RESP) {
      // Source is ready to recieve a response
      when (io.axi.rdata.fire) {
        state := TransferState.IDLE
      }
    }
  }
}




// Simple module that generates sequential 32-bit AXI read transations.
// This only interacts with the ar/r channels.
class TestAXISourceReadDriver extends Module {
  val io = IO(new Bundle {
    val result_addr = Output(UInt(4.W))
    val result_data = Output(UInt(32.W))
    val result_resp = Output(AXIRespType())
    val axi = AXIPort.source(4, 32)
  })
  io.axi := DontCare

  object TransferState extends ChiselEnum {
    val IDLE, WAIT = Value
  }

  val state       = RegInit(TransferState.IDLE) 
  val addr        = RegInit(0.U(4.W))
  val result_addr = RegInit(0.U(4.W))
  val result_data = RegInit(0.U(32.W))
  val result_resp = RegInit(AXIRespType.OKAY)

  // Single beat, fixed-burst, 4-byte transfer
  io.axi.raddr.bits.id    := "b100001".U
  io.axi.raddr.bits.addr  := addr
  io.axi.raddr.bits.size  := 4.U // 4-byte data
  io.axi.raddr.bits.len   := 1.U // 1 transfer
  io.axi.raddr.bits.burst := AXIBurstType.FIXED
  io.axi.raddr.bits.prot  := 0.U
  io.axi.raddr.bits.lock  := 0.U
  io.axi.raddr.bits.cache := 0.U
  io.axi.raddr.bits.qos   := 0.U

  // Output on 'raddr' is valid when we aren't waiting for a response
  io.axi.raddr.valid := (state === TransferState.IDLE)
  // We're ready to receive input on 'rdata' when we're waiting
  io.axi.rdata.ready := (state === TransferState.WAIT)

  // State machine
  switch (state) {
    is (TransferState.IDLE) {
      when (io.axi.raddr.fire) {
        state := TransferState.WAIT
      }
    }
    is (TransferState.WAIT) {
      when (io.axi.rdata.fire) {
        assert( io.axi.rdata.bits.resp === AXIRespType.OKAY )
        assert( io.axi.rdata.bits.last === true.B )
        result_addr := addr
        result_data := io.axi.rdata.bits.data
        result_resp := io.axi.rdata.bits.resp
        state       := TransferState.IDLE
        addr        := addr + 1.U
      }
    }
  }

  io.result_addr := result_addr
  io.result_data := result_data
  io.result_resp := result_resp
}



