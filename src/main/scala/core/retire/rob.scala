
package zno.core.retire

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._

class ReorderBuffer(implicit p: ZnoParam) extends Module {
  //val num_free = IO(Output(UInt(log2Ceil(p.rob_sz+1).W)))
  //num_free := p.rob_sz.U
  //val io = IO(new Bundle {
  //  val ap = Vec(1, new ROBAllocPort)
  //  val wp = Vec(1, new ROBWritePort)
  //  val rp = Vec(1, Valid(new ROBEntry))
  //})

  //val head = RegInit(0.U(robwidth.W))
  //val tail = RegInit(0.U(robwidth.W))
  //val data = RegInit(VecInit(Seq.fill(rob_sz)(ROBEntry())))
  //val can_alloc = ((head + 1.U) =/= tail)
  //val is_empty  = (head === tail)

  //io.ap(0).idx.valid := false.B
  //io.ap(0).idx.bits  := 0.U
  //io.rp(0).valid := false.B
  //io.rp(0).bits  := ROBEntry()

  //// Allocate a new entry
  //when (io.ap(0).en && can_alloc) {
  //  io.ap(0).idx.valid := true.B
  //  io.ap(0).idx.bits  := head
  //  data(head).rd      := io.ap(0).rd
  //  data(head).pd      := io.ap(0).pd
  //  data(head).done    := false.B
  //  head               := head + 1.U
  //}

  //// Mark an entry as complete
  //when (io.wp(0).en) {
  //  data(io.wp(0).idx).done := true.B
  //}

  //// Release an entry
  //when (!is_empty && data(tail).done) {
  //  io.rp(0).valid := true.B
  //  io.rp(0).bits  := data(tail)
  //  data(tail)     := ROBEntry()
  //  tail           := tail + 1.U
  //}
  //printf("[ROB] head=%x tail=%x \n",
  //  head, tail
  //)
}


