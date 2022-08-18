
package zno.riscv.hart

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.riscv.isa._
import zno.riscv.uarch._
import zno.riscv.decode._
import zno.riscv.dbg._

class Hart extends Module {
  val dbg      = IO(Output(new DebugOutput))
  val ibus     = IO(new DebugBus)
  val dbus     = IO(new DebugBus)

  val pc       = RegInit(0x00000000.U(32.W))
  val fetch    = Module(new FetchUnit)
  val decode   = Module(new DecodeUnit)
  val rf       = Module(new RegisterFile)
  val alu      = Module(new ArithmeticLogicUnit)
  val bcu      = Module(new BranchCompareUnit)
  val lsu      = Module(new LoadStoreUnit)

  val imm_en   = WireDefault(false.B)
  val rs1_en   = WireDefault(false.B)
  val rs2_en   = WireDefault(false.B)
  val rs1_data = WireDefault(0.U(32.W))
  val rs2_data = WireDefault(0.U(32.W))
  val imm_data = WireDefault(0.U(32.W))

  dbg.pc      := pc
  dbg.if_inst := fetch.io.inst
  dbg.id_uop  := decode.io.uop

  fetch.ibus     <> ibus
  lsu.dbus       <> dbus

  fetch.io.pc    := pc
  decode.io.inst := fetch.io.inst
  decode.io.pc   := pc

  val uop = decode.io.uop
  imm_en   := (uop.enc =/= InstEnc.ENC_R)
  rs1_en   := (
    uop.enc === InstEnc.ENC_R || uop.enc === InstEnc.ENC_I ||
    uop.enc === InstEnc.ENC_S || uop.enc === InstEnc.ENC_B
  )
  rs2_en   := (
    uop.enc === InstEnc.ENC_R || uop.enc === InstEnc.ENC_S ||
    uop.enc === InstEnc.ENC_B
  )

  // Defaults for read ports
  rf.io.rp(0).addr := Mux(rs1_en, uop.rs1, 0.U)
  rf.io.rp(1).addr := Mux(rs2_en, uop.rs2, 0.U)

  // Defaults for write ports
  rf.io.wp(0).addr := 0.U
  rf.io.wp(0).data := 0.U
  rf.io.wp(0).en   := false.B

  rs1_data         := Mux(rs1_en, rf.io.rp(0).data, 0.U)
  rs2_data         := Mux(rs2_en, rf.io.rp(1).data, 0.U)
  imm_data         := Mux(imm_en, uop.imm, 0.U)

  alu.io.op := ALUOp.ALU_NOP
  alu.io.pc := pc
  alu.io.x  := 0.U
  alu.io.y  := 0.U

  bcu.io.op  := BCUOp.BCU_NOP
  bcu.io.pc  := pc
  bcu.io.rs1 := 0.U
  bcu.io.rs2 := 0.U
  bcu.io.off := 0.U

  lsu.io.op   := LSUOp.LSU_NOP
  lsu.io.pc   := pc
  lsu.io.base := 0.U
  lsu.io.src  := 0.U
  lsu.io.off  := 0.U

  switch (decode.io.uop.eu) {
    is (ExecutionUnit.EU_ALU) {
      alu.io.op := uop.aluop
      alu.io.pc := uop.pc
      alu.io.x  := rs1_data
      alu.io.y  := Mux(imm_en, imm_data, rs2_data)

    }
    is (ExecutionUnit.EU_BCU) {
      bcu.io.op  := uop.bcuop
      bcu.io.pc  := uop.pc
      bcu.io.rs1 := rs1_data
      bcu.io.rs2 := rs2_data
      bcu.io.off := imm_data
    }
    is (ExecutionUnit.EU_LSU) {
      lsu.io.op   := uop.lsuop
      lsu.io.pc   := uop.pc
      lsu.io.base := rs1_data
      lsu.io.src  := rs2_data
      lsu.io.off  := imm_data
    }
    is (ExecutionUnit.EU_ILL) { 
    }
  }

  // Register file write
  rf.io.wp(0).en   := alu.io.rr
  rf.io.wp(0).addr := Mux(alu.io.rr, uop.rd, 0.U)
  rf.io.wp(0).data := Mux(alu.io.rr, alu.io.res, 0.U)

  // Branch/next-sequential instruction
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

  printf("fetch %x from %x\n", io.inst, io.pc)

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

  printf(p"ALU op=${io.op} x=${io.x} y=${io.y}\n")

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

  // Default
  io.ok  := false.B
  io.tgt := 0.U
  io.err := false.B

  // Evaluate the condition associated with this branch
  switch (io.op) {
    is (BCU_ILL) {
      io.err := true.B
    }
    is (BCU_EQ)   { 
      io.ok  := (io.rs1 === io.rs2) 
      io.tgt := io.pc + io.off 
    }
    is (BCU_NEQ)  { 
      io.ok := (io.rs1 =/= io.rs2) 
      io.tgt := io.pc + io.off 
    }
    is (BCU_LT)   { 
      io.ok := (io.rs1.asSInt < io.rs2.asSInt) 
      io.tgt := io.pc + io.off 
    }
    is (BCU_LTU)  { 
      io.ok := (io.rs1 < io.rs2) 
      io.tgt := io.pc + io.off 
    }
    is (BCU_GE)   { 
      io.ok := (io.rs1.asSInt >= io.rs2.asSInt) 
      io.tgt := io.pc + io.off 
    }
    is (BCU_GEU)  { 
      io.ok := (io.rs1 >= io.rs2) 
      io.tgt := io.pc + io.off 
    }
    is (BCU_JAL)  { 
      io.ok := true.B 
      io.tgt := io.pc + io.off 
    }
    is (BCU_JALR) { 
      io.ok := true.B 
      io.tgt := io.pc + (io.rs1 + io.off)
    }
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

    val res  = Output(Bool())
    val data = Output(UInt(32.W))
  })

  // Defaults
  dbus.addr  := 0.U
  dbus.wid   := DebugWidth.NONE
  dbus.wdata := 0.U
  dbus.wen   := false.B
  io.res     := false.B
  io.data    := 0.U

  val addr = Wire(UInt(32.W))
  addr := io.base + io.off
  switch (io.op) {
    is (LSU_LB) { 
      printf("load byte addr=%x\n", addr) 
      dbus.addr  := addr
      dbus.wid   := DebugWidth.BYTE
      dbus.wdata := 0.U
      dbus.wen   := false.B
      io.res     := true.B
      io.data    := dbus.data
    }
    is (LSU_LH) { 
      printf("load half addr=%x\n", addr)
      dbus.addr  := addr
      dbus.wid   := DebugWidth.HALF
      dbus.wdata := 0.U
      dbus.wen   := false.B
      io.res     := true.B
      io.data    := dbus.data
    }
    is (LSU_LW) { 
      printf("load word addr=%x\n", addr)
      dbus.addr  := addr
      dbus.wid   := DebugWidth.WORD
      dbus.wdata := 0.U
      dbus.wen   := false.B
      io.res     := true.B
      io.data    := dbus.data
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

class RegisterFile extends Module {
  val io = IO(new Bundle {
    val rp = Vec(2, new RFReadPort)
    val wp = Vec(1, new RFWritePort)
  })

  // NOTE: yosys will turn this into 1K FFs?
  //val reg = Reg(Vec(32, UInt(32.W)))
  //for (wp <- io.wp)
  //  when (wp.en) {
  //    reg(wp.addr) := wp.data
  //  }
  //for (rp <- io.rp)
  //  rp.data := reg(rp.addr)

  // NOTE: yosys turns this into RAM32M elements.
  // Haven't checked if it works beyond a 2r1w configuration
  val reg = Mem(32, UInt(32.W))
  for (wp <- io.wp) {
    when (wp.en) { reg.write(wp.addr, wp.data) }
  }
  for (rp <- io.rp) {
    rp.data := reg.read(rp.addr)
  }

}


