

pub mod rv32i;
pub mod mem;
pub mod state;

pub mod bpred;
pub mod front;
pub mod dispatch;

pub mod pipeline;

use goblin::*;
use std::collections::*;
use std::ops::*;

use crate::mem::*;
use crate::rv32i::*;
use crate::state::*;
use crate::bpred::*;
use crate::front::*;
use crate::dispatch::*;

fn read_prog(ram: &mut Ram, filename: &'static str) -> usize { 
    let buffer = std::fs::read("programs/test.elf").unwrap();
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


    //let mut btb = BTreeMap::<usize, BlockEntry>::new();
    //let mut fetch_pc = entry; 
    //let mut bb_start = entry;
    //for cyc in 0..8 
    //{ 
    //    // Fallback next pc
    //    let mut next_fetch_pc = fetch_pc.wrapping_add(32);

    //    // ----------------------------------
    //    // Fetch
    //    let mut fetch_blk = [0u8; 32];
    //    ram.read_bytes(fetch_pc, &mut fetch_blk);
    //    let decode_buf: [Instr; 8] = unsafe { 
    //        let buf: [u32; 8] = core::mem::transmute(fetch_blk);
    //        let mut res = [Instr::Illegal(0); 8];
    //        for i in 0..8 {
    //            res[i] = Rv32::decode(buf[i]);
    //        }
    //        res
    //    };

    //    // ----------------------------------
    //    // BTB access
    //    if let Some(btb_entry) = btb.get(&fetch_pc) {
    //    }


    //    // ----------------------------------
    //    // Decode + branch discovery
    //    let found_branch = decode_buf.iter().enumerate().find(|(idx, inst)| {
    //        inst.branch_info() != BranchInfo::None
    //    }).take();

    //    // ----------------------------------
    //    // Branch discovery
    //    if let Some((idx, inst)) = found_branch {
    //        let brn_pc = fetch_pc.wrapping_add(idx * 4);
    //        let btb_entry = BlockEntry { 
    //            off: idx * 4,
    //            brn: inst.branch_info(),
    //            pred: PredictionData::None,
    //        };
    //        println!("blk {:08x}: {:x?}", fetch_pc, btb_entry);
    //        btb.insert(fetch_pc, btb_entry);
    //    }

    //    fetch_pc = next_fetch_pc;
    //}

