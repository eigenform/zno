#![allow(unused_variables)]
#![allow(unused_parens)]
#![allow(unused_imports)]
#![allow(unused_mut)]
#![allow(dead_code)]
#![allow(unreachable_patterns)]

extern crate goblin;
use goblin::*;
use std::collections::*;
use std::rc::Rc;
use std::cell::*;
use std::any::*;

use zno_model::sim::*;
use zno_model::common::*;
use zno_model::soc::mem::*;
use zno_model::riscv::rv32i::*;

use zno_model::core::uarch::*;
use zno_model::core::sched::*;
use zno_model::core::rename::*;

fn read_prog(ram: &mut Ram, filename: &'static str) -> usize { 
    let buffer = std::fs::read(filename).unwrap();
    let elf = elf::Elf::parse(&buffer).unwrap();
    let entry = elf.entry as usize;
    for hdr in elf.program_headers {
        if hdr.p_type == elf64::program_header::PT_LOAD {
            let off = hdr.p_offset as usize;
            let dst = hdr.p_paddr as usize;
            let sz  = hdr.p_filesz as usize;
            ram.write_bytes(dst, &buffer[off..off+sz]);
        }
    }
    entry
}

fn main() {
    const RAM_SIZE: usize = 0x0200_0000;
    let mut ram = Ram::new(RAM_SIZE);
    let entry = read_prog(&mut ram, "programs/test.elf");

    let mut npc = Reg::<Option<usize>>::new(Some(entry));

    for cyc in 0..8 {
        println!("============== cycle {} ================", cyc);

        if let Some(pc) = npc.sample() {
            npc.drive(None);
            let fetch_addr = pc & !(0x1f);
            let idx = (pc & 0x1f) >> 2;
            let mut fblk = FetchBlock { 
                start: idx,
                addr: fetch_addr, 
                data: [0; 0x20]
            };
            ram.read_bytes(fetch_addr, &mut fblk.data);
            println!("[IFU] Fetched {:08x}", fblk.addr);

        }


        npc.update();
    }


}
