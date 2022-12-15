
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
    instr: Instr,
}


fn main() {

    let mut ram = Ram::new(0x0200_0000);
    let entry   = read_prog(&mut ram, "programs/test.elf") as u32;

    let mut d = ClockedState::new("top");
    // Next fetch PC
    let r_nfpc = d.track(Register::<u32>::new_init("nfpc", entry));
    // Instruction byte queue
    let q_ibq  = d.track(Queue::<FetchResp, 32>::new("ibq"));
    // Instruction queue
    let pq_iq  = d.track(PacketQueue::<DecodeOut, 32, 8>::new("iq"));
    // Freelist
    let m_frl  = d.track(Freelist::<256, 8>::new());


    for cyc in 0..32 {
        {
            println!("======== Cycle {:08x} ========", cyc);
            let ibq = q_ibq.borrow();
            let iq  = pq_iq.borrow(); 
            let frl = m_frl.borrow(); 
            println!("[**] IBQ:  {:03}/{:03}", ibq.num_used(), ibq.capacity());
            println!("[**] IBUF: {:03}/{:03}", iq.num_used(), iq.capacity());
            println!("[**] FRL:  {:03}/{:03}", frl.num_free(), frl.capacity());
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
            let mut iq = pq_iq.borrow_mut();
            let mut ibq = q_ibq.borrow_mut();
            let iq_max = iq.num_in();
            println!("[ID] iq_max = {}", iq_max);
            let decode_stall = {
                ibq.is_empty() ||
                iq.is_full()
            };
            if !decode_stall {
                let fr: &FetchResp = ibq.output().unwrap();
                let dw: [u32; 8] = unsafe { std::mem::transmute(fr.data) };
                let mut out = Packet::<DecodeOut, 8>::new();
                for idx in 0..iq_max {
                    let instr_off = idx * 4;
                    let instr_pc  = fr.pc.wrapping_add(instr_off as u32);
                    let instr     = Rv32::decode(dw[idx]);
                    out[idx]      = DecodeOut { pc: instr_pc, instr };
                }
                out.dump("decode window");
                if iq_max == 8 {
                    ibq.pop();
                    iq.push(out);
                } else if iq_max < 8 {
                    unimplemented!();
                } else { 
                    panic!();
                }
            } else {
                iq.push(Packet::new());
                println!("[ID] Stalled");
            }
        }

        // -------------------------------------------------------
        // Rename stage (resource allocation)
        {
            let mut frl = m_frl.borrow_mut();
            let mut iq  = pq_iq.borrow_mut();
            let rename_stall = {
                frl.is_full() || 
                iq.is_empty()
            };

            if !rename_stall {
                let window  = iq.output();
                let frl_out = frl.output();
                window.dump("rename window");
                frl_out.dump("freelist output");

                let num_preg_allocs = window.iter().enumerate()
                    .filter(|(idx, x)| x.instr.rd().is_some())
                    .map(|(idx, x)| idx).count();

            } else {
                println!("[RN] Rename stall");
            }
        }
 
        d.update();
    }
}


