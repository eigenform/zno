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

class AXIAddrChannel(addr_width: Int, id_width: Int = 6) extends Bundle {
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

class AXIWriteDataChannel(data_width: Int) extends Bundle {
  val data = UInt(data_width.W)
  val strb = UInt((data_width/8).W)
  val last = Bool()
}

class AXIWriteRespChannel(id_width: Int = 6) extends Bundle {
  val id   = UInt(id_width.W)
  val resp = AXIRespType()
}

class AXIReadDataChannel(data_width: Int, id_width: Int = 6) extends Bundle {
  val data = UInt(data_width.W)
  val id   = UInt(id_width.W)
  val last = Bool()
  val resp = AXIRespType()
}

// An AXI source port (outbound transactions).
class AXISourcePort(addr_width: Int, data_width: Int) extends Bundle {
  val waddr = Decoupled(new AXIAddrChannel(addr_width))
  val wdata = Decoupled(new AXIWriteDataChannel(data_width))
  val wresp = Flipped(Decoupled(new AXIWriteRespChannel()))
  val raddr = Decoupled(new AXIAddrChannel(addr_width))
  val rdata = Flipped(Decoupled(new AXIReadDataChannel(data_width)))
}

// An AXI sink port (inbound transactions).
class AXISinkPort(addr_width: Int, data_width: Int) extends Bundle {
  val waddr = Flipped(Decoupled(new AXIAddrChannel(addr_width)))
  val wdata = Flipped(Decoupled(new AXIWriteDataChannel(data_width)))
  val wresp = Decoupled(new AXIWriteRespChannel())
  val raddr = Flipped(Decoupled(new AXIAddrChannel(addr_width)))
  val rdata = Decoupled(new AXIReadDataChannel(data_width))
}


// I'm kind of relying on Vivado (grahhgrg) to integrate a design right now.
// It's nice to define a bundle that Vivado will automatically recognize as 
// an AXI interface.
//
// NOTE: Is there a way to do with with [[DataView]]?
class AXIExternalPort(addr_width: Int, data_width: Int, id_width: Int = 6) 
  extends Bundle 
{
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

  // Transactions move from the [AXISourcePort] to this object.
  def connect_axi_source(wire: AXISourcePort): Unit = {
    wire.waddr.ready      := this.AWREADY
    this.AWVALID          := wire.waddr.valid
    this.AWID             := wire.waddr.bits.id
    this.AWADDR           := wire.waddr.bits.addr
    this.AWLEN            := wire.waddr.bits.len
    this.AWSIZE           := wire.waddr.bits.size
    this.AWBURST          := wire.waddr.bits.burst
    this.AWLOCK           := wire.waddr.bits.lock
    this.AWCACHE          := wire.waddr.bits.cache
    this.AWPROT           := wire.waddr.bits.prot
    this.AWQOS            := wire.waddr.bits.qos

    wire.wdata.ready      := this.WREADY
    this.WVALID           := wire.wdata.valid
    this.WDATA            := wire.wdata.bits.data
    this.WLAST            := wire.wdata.bits.last
    this.WSTRB            := wire.wdata.bits.strb

    this.BREADY           := wire.wresp.ready
    wire.wresp.valid      := this.BVALID
    wire.wresp.bits.id    := this.BID
    wire.wresp.bits.resp  := this.BRESP

    wire.raddr.ready      := this.ARREADY
    this.ARVALID          := wire.raddr.valid
    this.ARID             := wire.raddr.bits.id
    this.ARADDR           := wire.raddr.bits.addr
    this.ARLEN            := wire.raddr.bits.len
    this.ARSIZE           := wire.raddr.bits.size
    this.ARBURST          := wire.raddr.bits.burst
    this.ARLOCK           := wire.raddr.bits.lock
    this.ARCACHE          := wire.raddr.bits.cache
    this.ARPROT           := wire.raddr.bits.prot
    this.ARQOS            := wire.raddr.bits.qos

    this.RREADY           := wire.rdata.ready
    wire.rdata.valid      := this.RVALID
    wire.rdata.bits.data  := this.RDATA
    wire.rdata.bits.resp  := this.RRESP
    wire.rdata.bits.id    := this.RID
    wire.rdata.bits.last  := this.RLAST
  }

  // Transactions move from this object to the [AXISinkPort].
  def connect_axi_sink(wire: AXISinkPort): Unit = {
    this.AWREADY            := wire.waddr.ready
    wire.waddr.valid        := this.AWVALID
    wire.waddr.bits.id      := this.AWID
    wire.waddr.bits.addr    := this.AWADDR
    wire.waddr.bits.len     := this.AWLEN
    wire.waddr.bits.size    := this.AWSIZE
    wire.waddr.bits.burst   := this.AWBURST
    wire.waddr.bits.lock    := this.AWLOCK
    wire.waddr.bits.cache   := this.AWCACHE
    wire.waddr.bits.prot    := this.AWPROT
    wire.waddr.bits.qos     := this.AWQOS
                          
    this.WREADY             := wire.wdata.ready
    wire.wdata.valid        := this.WVALID
    wire.wdata.bits.data    := this.WDATA
    wire.wdata.bits.last    := this.WLAST
    wire.wdata.bits.strb    := this.WSTRB
                          
    wire.wresp.ready        := this.BREADY
    this.BVALID             := wire.wresp.valid
    this.BID                := wire.wresp.bits.id
    this.BRESP              := wire.wresp.bits.resp
                          
    this.ARREADY            := wire.raddr.ready
    wire.raddr.valid        := this.ARVALID
    wire.raddr.bits.id      := this.ARID
    wire.raddr.bits.addr    := this.ARADDR
    wire.raddr.bits.len     := this.ARLEN
    wire.raddr.bits.size    := this.ARSIZE
    wire.raddr.bits.burst   := this.ARBURST
    wire.raddr.bits.lock    := this.ARLOCK
    wire.raddr.bits.cache   := this.ARCACHE
    wire.raddr.bits.prot    := this.ARPROT
    wire.raddr.bits.qos     := this.ARQOS
                          
    wire.rdata.ready        := this.RREADY
    this.RVALID             := wire.rdata.valid
    this.RDATA              := wire.rdata.bits.data
    this.RRESP              := wire.rdata.bits.resp
    this.RID                := wire.rdata.bits.id
    this.RLAST              := wire.rdata.bits.last
  }
}



