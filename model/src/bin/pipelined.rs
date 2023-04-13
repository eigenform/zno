
extern crate goblin;
use goblin::*;
use std::collections::*;
use std::ops::*;
use std::rc::*;
use std::cell::*;

use zno_model::mem::*;
use zno_model::state::*;
use zno_model::queue::*;

use zno_model::rv32i::*;
use zno_model::packet::*;
use zno_model::front::*;
use zno_model::dispatch::*;
use zno_model::retire::*;
use zno_model::uarch::*;
use zno_model::sched::*;
use zno_model::prim::*;


fn read_prog(ram: &mut Ram, filename: &'static str) -> usize { 
    let buffer = std::fs::read(filename).unwrap();
    let elf = elf::Elf::parse(&buffer).unwrap();
    let entry = elf.entry as usize;
    println!("entry {:08x}", entry);
    for hdr in elf.program_headers {
        if hdr.p_type == elf64::program_header::PT_LOAD {
            let off = hdr.p_offset as usize;
            let dst = hdr.p_paddr as usize;
            let sz  = hdr.p_filesz as usize;
            ram.write_bytes(dst, &buffer[off..off+sz]);
            println!("{:?}", hdr);
        }
    }
    entry
}

pub type CacheLine = [u8; 32];
#[derive(Clone, Copy, Debug, Default)]
pub struct FetchResp {
    pc: u32,
    data: CacheLine,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct DecodeOut {
    pc: u32,
    instr: [Instr; 8],
}


fn main() {

    let mut ram = Ram::new(0x0200_0000);
    let entry   = read_prog(&mut ram, "programs/test.elf") as u32;

    let mut d = ClockedState::new("top");
    // Next fetch PC
    let r_nfpc = d.track(Register::<u32>::new_init("nfpc", entry));
    // Instruction byte queue
    let q_ibq  = d.track(Queue::<FetchResp, 32>::new("ibq"));
    // Decoded instruction queue
    //let pq_idq  = d.track(PacketQueue::<DecodeOut, 32, 8>::new("idq"));
    let q_idq  = d.track(Queue::<DecodeOut, 32>::new("idq"));

    // Freelist
    let m_frl  = d.track(Freelist::<256, 8>::new());
    // Integer Scheduler
    let m_sch  = d.track(IntegerScheduler::<32, 8>::new());
    // Reorder buffer
    let m_rob  = d.track(ReorderBuffer::<512, 8>::new());


    for cyc in 0..32 {
        {
            println!("======== Cycle {:08x} ========", cyc);
            let ibq = q_ibq.borrow();
            let idq = q_idq.borrow(); 
            let frl = m_frl.borrow(); 
            let sch = m_sch.borrow();
            let rob = m_rob.borrow();
            println!("[**] IBQ:  {:03}/{:03}", ibq.num_used(), ibq.capacity());
            println!("[**] IBUF: {:03}/{:03}", idq.num_used(), idq.capacity());
            println!("[**] FRL:  {:03}/{:03}", frl.num_free(), frl.capacity());
            println!("[**] SCH:  {:03}/{:03}", sch.num_free(), sch.capacity());
            println!("[**] ROB:  {:03}/{:03}", rob.num_free(), rob.capacity());
        }

        // -------------------------------------------------------
        // Fetch stage
        {
            let mut ibq = q_ibq.borrow_mut();
            let fetch_stall = ibq.is_full();
            if !fetch_stall {
                let fpc = r_nfpc.borrow().output();
                println!("[IF] Fetch from {:08x}", fpc);
                let mut data = [0u8; 32];
                ram.read_bytes(fpc as usize, &mut data);
                ibq.push(FetchResp { pc: fpc, data });
                r_nfpc.borrow_mut().assign(fpc.wrapping_add(32));
            } else {
                println!("[IF] Stalled");
            }
        }

        // -------------------------------------------------------
        // Decode stage
        {
            let mut idq = q_idq.borrow_mut();
            let mut ibq = q_ibq.borrow_mut();
            let decode_stall = {
                ibq.is_empty() ||
                idq.is_full()
            };
            if !decode_stall {
                let fr: &FetchResp = ibq.output().unwrap();
                let dw: [u32; 8] = unsafe { std::mem::transmute(fr.data) };
                let mut out = DecodeOut { 
                    pc: fr.pc,
                    instr: [Instr::Illegal(0xdeadc0de); 8] 
                };
                for idx in 0..8 {
                    out.instr[idx] = Rv32::decode(dw[idx]);
                }
            } else {
                println!("[ID] Stalled");
            }
        }

        // -------------------------------------------------------
        // Dispatch stage (resource allocation)
        {
            let mut frl = m_frl.borrow_mut();
            let mut idq  = q_idq.borrow_mut();
            let mut sch = m_sch.borrow_mut();
            let mut rob = m_rob.borrow_mut();
            let window  = idq.output();

            let preg_free = frl.num_free();
            let sch_free  = sch.num_free();
            let rob_free  = rob.num_free();

            //window.dump("dispatch window");
            let mut num_preg_req = 0;
            let mut num_sch_req  = 0;
            let mut num_rob_req  = 0;
            let mut window_size  = 0;
            for (idx, entry) in window.valid_iter().enumerate() {
                let p_req = entry.instr.num_preg_allocs();
                let s_req = entry.instr.num_sch_allocs();
                let r_req = 1;
                if num_rob_req + r_req  >= rob_free  { break; }
                if num_preg_req + p_req >= preg_free { break; }
                if num_sch_req + s_req  >= sch_free  { break; }
                num_rob_req  += r_req;
                num_preg_req += p_req;
                num_sch_req  += s_req;
                window_size  += 1;
            }
            println!("[DS] Can dispatch {} entries", window_size);

            idq.consume(window_size);

            sch.push(Packet::new());
            rob.push(Packet::new());
        }
 
        d.update();
    }
}


