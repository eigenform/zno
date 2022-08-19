
package zno.riscv.hart

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.riscv.isa._
import zno.riscv.uarch._
import zno.riscv.decode._
import zno.riscv.dbg._

class Hart extends Module {
  import InstEnc._

  val dbg      = IO(Output(new DebugOutput))
  val ibus     = IO(new DebugBus)
  val dbus     = IO(new DebugBus)

  val pc       = RegInit(0x00000000.U(32.W))
  val fetch    = Module(new FetchUnit)
  val decode   = Module(new DecodeUnit)
  val rf       = Module(new RegisterFileBRAM)
  val alu      = Module(new ArithmeticLogicUnit)
  val bcu      = Module(new BranchCompareUnit)
  val lsu      = Module(new LoadStoreUnit)

  val uop      = decode.io.uop

  // Connect I/Os
  dbg.pc      := pc
  dbg.if_inst := fetch.io.inst
  dbg.id_uop  := decode.io.uop
  fetch.ibus  <> ibus
  lsu.dbus    <> dbus

  val imm_en   = (uop.enc =/= ENC_R)
  val rs1_en   = (uop.enc === ENC_R || uop.enc === ENC_I || 
                  uop.enc === ENC_S || uop.enc === ENC_B)
  val rs2_en   = (uop.enc === ENC_R || uop.enc === ENC_S || uop.enc === ENC_B)
  val rs1_data = Mux(rs1_en, rf.io.rp(0).data, 0.U)
  val rs2_data = Mux(rs2_en, rf.io.rp(1).data, 0.U)
  val imm_data = Mux(imm_en, uop.imm, 0.U)


  rf.io.rp(0).addr := Mux(rs1_en, uop.rs1, 0.U)
  rf.io.rp(1).addr := Mux(rs2_en, uop.rs2, 0.U)
  rf.io.wp(0).addr := 0.U
  rf.io.wp(0).data := 0.U
  rf.io.wp(0).en   := false.B

  alu.io.op   := ALUOp.ALU_NOP
  alu.io.pc   := pc
  alu.io.x    := 0.U
  alu.io.y    := 0.U
  bcu.io.op   := BCUOp.BCU_NOP
  bcu.io.pc   := pc
  bcu.io.rs1  := 0.U
  bcu.io.rs2  := 0.U
  bcu.io.off  := 0.U
  lsu.io.op   := LSUOp.LSU_NOP
  lsu.io.pc   := pc
  lsu.io.base := 0.U
  lsu.io.src  := 0.U
  lsu.io.off  := 0.U
  fetch.io.pc    := pc
  decode.io.pc   := pc
  decode.io.inst := fetch.io.inst

  alu.io.op   := uop.aluop
  alu.io.pc   := uop.pc
  alu.io.x    := rs1_data
  alu.io.y    := Mux(imm_en, imm_data, rs2_data)
  bcu.io.op   := uop.bcuop
  bcu.io.pc   := uop.pc
  bcu.io.rs1  := rs1_data
  bcu.io.rs2  := rs2_data
  bcu.io.off  := imm_data
  lsu.io.op   := uop.lsuop
  lsu.io.pc   := uop.pc
  lsu.io.base := rs1_data
  lsu.io.src  := rs2_data
  lsu.io.off  := imm_data

  // FIXME: load rr
  val has_rr = alu.io.rr || lsu.io.rr
  rf.io.wp(0).en   := has_rr
  rf.io.wp(0).addr := Mux(has_rr, uop.rd, 0.U)
  val res_data = Mux(lsu.io.rr, lsu.io.data, alu.io.res)
  rf.io.wp(0).data := Mux(has_rr, res_data, 0.U)

  pc := Mux(bcu.io.ok, bcu.io.tgt, (pc + 4.U))


}

class FetchUnit extends Module {
  val ibus = IO(new DebugBus)
  val io = IO(new Bundle {
    val pc   = Input(UInt(32.W))
    val inst = Output(UInt(32.W))
  })
  ibus.wen   := false.B
  ibus.wdata := 0.U
  ibus.addr  := io.pc
  ibus.wid   := DebugWidth.WORD
  io.inst    := ibus.data
}

class ArithmeticLogicUnit extends Module {
  import ALUOp._

  val io = IO(new Bundle {
    val pc  = Input(UInt(32.W))
    val op  = Input(ALUOp())
    val x   = Input(UInt(32.W))  // This is always RS1
    val y   = Input(UInt(32.W))  // This is either RS2/immediate
    val res = Output(UInt(32.W))
    val rr  = Output(Bool())     // This operation produces a register result
    val err = Output(Bool())
  })
  val shamt = io.y(4, 0).asUInt

  io.res := 0.U
  io.rr  := true.B
  io.err := false.B

  switch (io.op) {
    is (ALU_ILL) { 
      io.err := true.B 
      io.rr  := false.B
    }
    is (ALU_NOP) { 
      io.err := false.B 
      io.rr  := false.B 
    }

    is (ALU_AND)   { io.res := io.x & io.y }
    is (ALU_OR)    { io.res := io.x | io.y }
    is (ALU_ADD)   { io.res := io.x + io.y }
    is (ALU_SUB)   { io.res := io.x - io.y }
    is (ALU_SRA)   { io.res := (io.x.asSInt >> shamt).asUInt }
    is (ALU_SLTU)  { io.res := io.x < io.y }
    is (ALU_XOR)   { io.res := io.x ^ io.y }
    is (ALU_SRL)   { io.res := io.x >> shamt }
    is (ALU_SLT)   { io.res := (io.x.asSInt < io.y.asSInt).asUInt }
    is (ALU_SLL)   { io.res := io.x << shamt }

    is (ALU_LUI)   { io.res := io.y }
    is (ALU_AUIPC) { io.res := io.pc + io.y }
  }
  when (io.op =/= ALU_NOP) {
    printf("[ALU] x=%x y=%x res=%x\n", io.x, io.y, io.res)
  }
}

class BranchCompareUnit extends Module {
  import BCUOp._

  val io = IO(new Bundle {
    val pc  = Input(UInt(32.W))
    val off = Input(UInt(32.W))

    val op  = Input(BCUOp())
    val rs1 = Input(UInt(32.W))
    val rs2 = Input(UInt(32.W))

    val tgt = Output(UInt(32.W))
    val ok  = Output(Bool())
    val err = Output(Bool())
  })

  io.ok  := false.B
  io.tgt := 0.U
  io.err := (io.op === BCU_ILL)

  // JALR is the only exceptional case (the immediate is added to RS1)
  val base = Mux(io.op === BCU_JALR, io.rs1, io.pc)

  // Compute the target address.
  // (Just explicitly set to zero for BCU_NOP and BCU_ILL)
  io.tgt := MuxCase(
    ((base.asSInt + io.off.asSInt).asUInt), 
    Array(
      (io.op === BCU_NOP) -> 0.U,
      (io.op === BCU_ILL) -> 0.U,
    )
  )

  // Evaluate the condition associated with this branch
  switch (io.op) {
    is (BCU_NOP)  { io.ok := false.B }
    is (BCU_ILL)  { io.ok := false.B }
    is (BCU_EQ)   { io.ok := (io.rs1 === io.rs2) }
    is (BCU_NEQ)  { io.ok := (io.rs1 =/= io.rs2) }
    is (BCU_LT)   { io.ok := (io.rs1.asSInt < io.rs2.asSInt) }
    is (BCU_LTU)  { io.ok := (io.rs1 < io.rs2) }
    is (BCU_GE)   { io.ok := (io.rs1.asSInt >= io.rs2.asSInt) }
    is (BCU_GEU)  { io.ok := (io.rs1 >= io.rs2) }
    is (BCU_JAL)  { io.ok := true.B }
    is (BCU_JALR) { io.ok := true.B }
  }
}

class LoadStoreUnit extends Module {
  import LSUOp._

  val dbus = IO(new DebugBus)
  val io = IO(new Bundle {
    val pc   = Input(UInt(32.W))
    val op   = Input(LSUOp())
    val base = Input(UInt(32.W)) // This is always RS1
    val src  = Input(UInt(32.W)) // This is always RS2
    val off  = Input(UInt(32.W)) // This is always an immediate

    val rr  = Output(Bool())
    val data = Output(UInt(32.W))
  })

  // Defaults
  dbus.addr  := 0.U
  dbus.wid   := DebugWidth.NONE
  dbus.wdata := 0.U
  dbus.wen   := false.B
  io.rr     := false.B
  io.data    := 0.U

  val addr = Wire(UInt(32.W))
  addr := io.base + io.off
  switch (io.op) {
    is (LSU_LB) { 
      dbus.addr  := addr
      dbus.wid   := DebugWidth.BYTE
      dbus.wdata := 0.U
      dbus.wen   := false.B
      io.rr     := true.B
      io.data    := dbus.data
      printf("load byte addr=%x data=%x\n", addr, dbus.data) 
    }
    is (LSU_LH) { 
      dbus.addr  := addr
      dbus.wid   := DebugWidth.HALF
      dbus.wdata := 0.U
      dbus.wen   := false.B
      io.rr     := true.B
      io.data    := dbus.data
      printf("load half addr=%x data=%x\n", addr, dbus.data)
    }
    is (LSU_LW) { 
      dbus.addr  := addr
      dbus.wid   := DebugWidth.WORD
      dbus.wdata := 0.U
      dbus.wen   := false.B
      io.rr     := true.B
      io.data    := dbus.data
      printf("load word addr=%x data=%x\n", addr, dbus.data)
    }
    is (LSU_SB) { 
      printf("store byte addr=%x data=%x\n", addr, io.src)
      dbus.addr  := addr
      dbus.wdata := io.src
      dbus.wid   := DebugWidth.BYTE
      dbus.wen   := true.B
    }
    is (LSU_SH) { 
      printf("store half addr=%x data=%x\n", addr, io.src)
      dbus.addr  := addr
      dbus.wdata := io.src
      dbus.wid   := DebugWidth.HALF
      dbus.wen   := true.B
    }
    is (LSU_SW) { 
      printf("store word addr=%x data=%x\n", addr, io.src)
      dbus.addr  := addr
      dbus.wdata := io.src
      dbus.wid   := DebugWidth.WORD
      dbus.wen   := true.B
    }
  }
}

class RFReadPort extends Bundle {
  val addr = Input(UInt(5.W))
  val data = Output(UInt(32.W))
}
class RFWritePort extends Bundle {
  val addr = Input(UInt(5.W))
  val data = Input(UInt(32.W))
  val en   = Input(Bool())
}


class RegisterFileBRAM extends Module {
  val io = IO(new Bundle {
    val rp = Vec(2, new RFReadPort)
    val wp = Vec(1, new RFWritePort)
  })
  val reg = Mem(32, UInt(32.W))
  for (wp <- io.wp) {
    when (wp.en) { reg.write(wp.addr, wp.data) }
  }
  for (rp <- io.rp) {
    rp.data := reg.read(rp.addr)
  }
  printf("x1=%x x2=%x x3=%x x4=%x x5=%x x6=%x\n",
    reg.read(1.U), reg.read(2.U), reg.read(3.U), reg.read(4.U), reg.read(5.U), reg.read(6.U))
}


class RegisterFileFF extends Module {
  val io = IO(new Bundle {
    val rp = Vec(2, new RFReadPort)
    val wp = Vec(1, new RFWritePort)
  })
  val reg = Reg(Vec(32, UInt(32.W)))
  for (wp <- io.wp)
    when (wp.en) { reg(wp.addr) := wp.data }
  for (rp <- io.rp)
    rp.data := reg(rp.addr)
}


