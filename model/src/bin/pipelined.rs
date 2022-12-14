
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
    // Instruction byte buffer
    let q_ibb  = d.track(Queue::<FetchResp, 32>::new("ibb"));
    // Instruction buffer
    let pq_ibuf  = d.track(PacketQueue::<DecodeOut, 32, 8>::new("ibuf"));


    for cyc in 0..32 {
        println!("======== Cycle {:08x} ========", cyc);

        {
            let ibb = q_ibb.borrow();
            let ibuf = pq_ibuf.borrow(); 
            println!("[**] IBB:  {:02}/{:02}", ibb.len(), ibb.capacity());
            println!("[**] IBUF: {:02}/{:02}", ibuf.len(), ibuf.capacity());
        }

        // ---------------------------------------
        // Fetch stage
        {
            let mut ibb = q_ibb.borrow_mut();
            let fetch_stall = ibb.is_full();
            if !fetch_stall {
                let fpc = r_nfpc.borrow().output();
                println!("[IF] Fetch from {:08x}", fpc);
                let mut data = [0u8; 32];
                ram.read_bytes(fpc as usize, &mut data);
                ibb.push(FetchResp { pc: fpc, data });
                r_nfpc.borrow_mut().assign(fpc.wrapping_add(32));
            } else {
                println!("[IF] Stalled");
            }
        }

        // ---------------------------------------
        // Decode stage
        {
            let mut ibuf = pq_ibuf.borrow_mut();
            let mut ibb = q_ibb.borrow_mut();
            let ibuf_free = ibuf.num_enq();
            println!("[ID] ibuf_free = {}", ibuf_free);
            let decode_stall = {
                ibb.is_empty() ||
                ibuf.is_full()
            };
            if !decode_stall {
                let fr: &FetchResp = ibb.output().unwrap();
                let dw: [u32; 8] = unsafe { std::mem::transmute(fr.data) };
                let mut out = Packet::<DecodeOut, 8>::new();
                for idx in 0..ibuf_free {
                    let instr_off = idx * 4;
                    let instr_pc  = fr.pc.wrapping_add(instr_off as u32);
                    let instr     = Rv32::decode(dw[idx]);
                    out[idx] = DecodeOut { pc: instr_pc, instr };
                }
                out.dump("decode window");
                if ibuf_free == 8 {
                    ibb.pop();
                    ibuf.push(out);
                } else if ibuf_free < 8 {
                    unimplemented!();
                } else { 
                    panic!();
                }
            } else {
                ibuf.push(Packet::new());
                println!("[ID] Stalled");
            }
        }

        d.update();
    }
}


