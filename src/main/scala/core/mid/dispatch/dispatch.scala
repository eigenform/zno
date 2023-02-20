
package zno.core.mid.dispatch

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._

// Logic responsible for determining the current dispatch window.
class DispatchWindowCtrl(implicit p: ZnoParam) extends Module {
  // Consumer interface to the micro-op queue
  val opq     = IO(new FIFOConsumerIO(new Uop, p.id_width))

  // Signals carrying information about resource availability
  val rsrc    = IO(new Bundle {
    // Number of free physical registers
    val rfl = Input(UInt(log2Ceil(p.num_preg+1).W))
    // Number of free scheduler entries
    val sch = Input(UInt(log2Ceil(p.sch_sz+1).W))
    // Number of free reorder buffer entries
    val rob = Input(UInt(log2Ceil(p.rob_sz+1).W))
  })

  // Output containing the current dispatch window
  val dis_pkt = IO(new Packet(new Uop, p.id_width))

  // Determine how many micro-ops we can consume from the queue
  val rfl_free_tbl = (0 until p.id_width).map(i => (i.U < rsrc.rfl))
  val sch_free_tbl = (0 until p.id_width).map(i => (i.U < rsrc.sch))
  val rob_free_tbl = (0 until p.id_width).map(i => (i.U < rsrc.rob))
  val rr_req_tbl = opq.data.zipWithIndex.map({
    case (op,idx) => (op.rr && (idx.U < opq.len))
  })
  val sc_req_tbl = opq.data.zipWithIndex.map({
    //case (op,idx) => (op.schedulable() && (idx.U < opq.len))
    case (op,idx) => ((idx.U < opq.len))
  })
  val rb_req_tbl = (0 until p.id_width).map({
    case (idx) => (idx.U < opq.len)
  })
  val rr_ok_tbl = (rr_req_tbl zip rfl_free_tbl) map {
    case (req, free) => (!req || req && free)
  }
  val sc_ok_tbl = (sc_req_tbl zip sch_free_tbl) map {
    case (req, free) => (!req || req && free)
  }
  val rb_ok_tbl = (rb_req_tbl zip rob_free_tbl) map {
    case (req, free) => (!req || req && free)
  }
  val ok_tbl = (rr_ok_tbl zip sc_ok_tbl zip rb_ok_tbl) map {
    case ((rr, sc), rb) => rr && sc && rb
  }

  // Tell the micro-op queue how many entries we've consumed this cycle
  val num_ok = PopCount(ok_tbl)
  opq.take  := num_ok

  // The output dispatch window only contains micro-ops that are guaranteed
  // to be consumed/dispatched
  for (idx <- 0 until p.id_width) {
    when (idx.U < num_ok) {
      dis_pkt.data(idx) := opq.data(idx)
    } 
    .otherwise {
      dis_pkt.data(idx) := (0.U).asTypeOf(new Uop)
    }
  }
  dis_pkt.len := num_ok


  //printf("[Disp1]: free_preg=%d free_sch=%d free_rob=%d\n",
  //  rsrc.rfl, rsrc.sch, rsrc.rob)
  //for (i <- 0 until p.id_width) {
  //  printf("[Disp1]: req[%x] v=%b pc=%x rd=%x rs1=%x rs2=%x\n",
  //    i.U, (i.U < opq.len),
  //    opq.data(i).pc, opq.data(i).rd,
  //    opq.data(i).rs1, opq.data(i).rs2
  //  )
  //  printf("  op[%x] has_rr=%b sched=%b ok=%b\n", 
  //    i.U, rr_req_tbl(i), sc_req_tbl(i), ok_tbl(i)
  //  )
  //}
}


// The dispatch stage, separating the front-end from the back-end. 
class DispatchStage(implicit p: ZnoParam) extends Module {

  val opq     = IO(new FIFOConsumerIO(new Uop, p.id_width))
  val opq_w   = Wire(new FIFOProducerIO(new Uop, p.id_width))
  opq_w       <> opq

  val rsrc    = IO(new Bundle { 
    // Number of free scheduler entries
    val sch_free = Input(UInt(log2Ceil(p.sch_sz+1).W))
    // Number of free reorder buffer entries
    val rob_free = Input(UInt(log2Ceil(p.rob_sz+1).W))
  })

  val rob_alc = IO(new Packet(new ROBEntry, p.id_width))

  val dis_ctl = Module(new DispatchWindowCtrl)
  val rrn     = Module(new RegisterRename)

  // These are stateful components local to the dispatch stage
  val rfl     = Module(new RegisterFreeList)
  val map     = Module(new RegisterMap)

  // Inputs to dispatch window control
  dis_ctl.opq      <> opq_w
  dis_ctl.rsrc.rfl := rfl.num_free
  dis_ctl.rsrc.sch := rsrc.sch_free
  dis_ctl.rsrc.rob := rsrc.rob_free

  // Inputs to register rename
  rrn.req     := dis_ctl.dis_pkt
  rrn.rfl_alc <> rfl.alc
  rrn.map_rp  <> map.rp
  rrn.map_wp  <> map.wp

  // Renamed entries in the dispatch window are sent to the scheduler
  // and reorder buffer
  val rrn_pkt  = Wire(new Packet(new Uop, p.id_width))
  rrn_pkt     := rrn.res

  rob_alc.len := rrn_pkt.len
  for (idx <- 0 until p.id_width) {
    rob_alc.data(idx).rd   := rrn_pkt.data(idx).rd
    rob_alc.data(idx).pd   := rrn_pkt.data(idx).pd
    rob_alc.data(idx).done := false.B
  }



}


