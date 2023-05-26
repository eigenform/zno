
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

use zno_model::soc::mem::*;
use zno_model::riscv::rv32i::*;
use zno_model::sim::*;

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

/// Content-addressible memory with 1-cycle read/write
struct SyncReadCam<K: Ord, V: Copy, const NUM_RP: usize> {
    next: Option<(K, V)>,
    wp_pending: Vec<(K, V)>,

    rp_key: [Option<K>; NUM_RP],
    rp_val: [Option<V>; NUM_RP],

    data: BTreeMap<K, V>,
}
impl <K: Ord, V: Copy, const NUM_RP: usize> SyncReadCam<K, V, NUM_RP> {
    /// Drive a new element (available on the next cycle).
    pub fn write(&mut self, k: K, v: V) {
        self.wp_pending.push((k, v));
    }

    pub fn drive_rp(&mut self, port: usize, k: K) {
        self.rp_key[port] = Some(k);
    }
    pub fn sample_rp(&self, port: usize) -> Option<V> {
        self.rp_val[port]
    }

    pub fn update(&mut self) {
        for idx in 0..NUM_RP {
            if let Some(key) = self.rp_key[idx].take() {
                if let Some(value) = self.data.get(&key) {
                    self.rp_val[idx] = Some(*value);
                } else {
                    self.rp_val[idx] = None;
                }
            } else {
                self.rp_val[idx] = None;
            }
        }
    }
}

struct Queue<T> {
    next: Option<T>,
    deq_ok: bool,
    data: VecDeque<T>,
}
impl <T> Queue<T> {
    pub fn new() -> Self {
        Self {
            next: None,
            deq_ok: false,
            data: VecDeque::new(),
        }
    }

    /// Drive a new element onto the queue for this cycle, indicating that
    /// the new element will be present in the queue after the next update.
    pub fn enq(&mut self, data: T) {
        self.next = Some(data);
    }

    /// Drive the 'deq_ok' signal for this cycle, indicating that 
    /// the oldest entry in the queue will be removed after the next update.
    pub fn set_deq(&mut self) {
        self.deq_ok = true;
    }

    /// Get the oldest entry in the queue (if it exists).
    pub fn front(&self) -> Option<&T> {
        self.data.front()
    }

    /// Update the state of the queue. 
    pub fn update(&mut self) {
        // Add a new element being driven this cycle
        if let Some(next) = self.next.take() {
            self.data.push_back(next);
        }
        // Remove the oldest element if it was marked as consumed this cycle
        if self.deq_ok {
            self.data.pop_front();
            self.deq_ok = false;
        }
    }
}

pub struct FetchBlock {
    addr: usize,
    data: [u8; 0x20],
}
impl FetchBlock {
    pub fn as_words(&self) -> &[u32; 8] {
        unsafe { std::mem::transmute(&self.data) }
    }
}
pub struct DecodeBlock {
    addr: usize,
    data: [Instr; 8],
}

fn main() {
    const RAM_SIZE: usize = 0x0200_0000;
    let mut ram = Ram::new(RAM_SIZE);
    let entry = read_prog(&mut ram, "programs/test.elf");

    let mut ftq: Queue<usize> = Queue::new();
    let mut fbq: Queue<FetchBlock> = Queue::new();
    let mut dbq: Queue<DecodeBlock> = Queue::new();
    ftq.enq(entry);
    ftq.update();

    for cyc in 0..8 {
        println!("-------- cycle {} -------------", cyc);

        // Take the pending fetch address and fetch the appropriate block.
        // Pop the fetch address and push a new fetch block.
        if let Some(fetch_addr) = ftq.front() {
            let mut fblk = FetchBlock { addr: *fetch_addr, data: [0; 0x20] };
            ram.read_bytes(*fetch_addr, &mut fblk.data);
            println!("Fetched fblk {:08x}", fblk.addr);
            fbq.enq(fblk);
            ftq.set_deq();
        }

        // Take the pending fetch block and predecode it. 
        // Pop the fetch block and push a new decode block.
        if let Some(fblk) = fbq.front() {
            let mut dblk = DecodeBlock {
                addr: fblk.addr,
                data: Rv32::decode_arr(fblk.as_words()),
            };
            println!("Decoded fblk {:08x}", dblk.addr);
        }




        // Take the pending fetch block and decode it. 
        // Pop the fetch block and push a new decode block.
        if let Some(fblk) = fbq.front() {
            let mut dblk = DecodeBlock {
                addr: fblk.addr,
                data: Rv32::decode_arr(fblk.as_words()),
            };
            println!("Decoded fblk {:08x}", dblk.addr);
            dbq.enq(dblk);
            fbq.set_deq();
        }

        // Put the next-sequential fetch address on the FTQ
        if let Some(fetch_addr) = ftq.front() {
            let next_fetch_addr = fetch_addr.wrapping_add(0x20);
            println!("Put {:08x} on FTQ", next_fetch_addr);
            ftq.enq(next_fetch_addr);
        }

        // Update queues
        ftq.update();
        fbq.update();
        dbq.update();
    }
}


//    pub fn npc(&mut self) {
//        match self.npcstage.npc {
//            NPCValue::Reset => {
//                self.ftq.clear();
//                self.ftq.push_back(FtqEntry { 
//                    addr: self.reset_vector,
//                    spec: false,
//                });
//            },
//            _ => unimplemented!("{:?}", self.npcstage.npc),
//        }
//
//        // CFM access technically starts here ..
//    }
//
//    /// Instruction fetch
//    pub fn fetch(&mut self) {
//
//        if let Some(ftq_ent) = self.ftq.get(0) { 
//            println!("[IF] pc={:08x} spec={}", ftq_ent.addr, ftq_ent.spec);
//            let mut fblk = [0; 0x40];
//            self.ram.read_bytes(ftq_ent.addr, &mut fblk);
//            let fbq_entry = FbqEntry { 
//                addr: ftq_ent.addr,
//                spec: ftq_ent.spec,
//                data: fblk,
//            };
//            self.fbq.push_back(fbq_entry);
//            self.ftq.pop_front();
//        }
//
//        // Pipeline registers
//        let fstage = FetchStage { 
//            pc: fetch_pc, 
//            blk, 
//            cfm_ent, 
//            spec: self.npcstage.spec 
//        };
//        self.fstage = Some(fstage);
//
//        // FIXME
//        //self.npcstage.npc = NPCValue::NextSeq(fetch_pc);
//        self.npcstage.npc = NPCValue::None;
//    }
//
//    /// Instruction predecode
//    pub fn predecode(&mut self) {
//        if let Some(stage) = &self.fstage { 
//            println!("[PD] pc={:08x} spec={}", stage.pc, stage.spec);
//
//            match stage.cfm_ent {
//                None => {
//                    println!("[PD] CFM miss for {:08x}", stage.pc);
//                },
//                Some(cfm_ent) => {
//                    println!("[PD] CFM hit for {:08x}", stage.pc);
//                },
//
//            }
//
//
//            // Predecoders
//            let words: [u32; 8] = unsafe { std::mem::transmute(stage.blk) };
//            let mut inst = [Instr::Illegal(0xdeadc0de); 8];
//            let mut info = {
//                let mut res = [None; 8];
//                for i in 0..8 {
//                    let inst = Rv32::decode(words[i]);
//                    res[i] = inst.branch_info();
//                }
//                res
//            };
//
//            let mut terminal = CfmEntryKind::Fallthrough;
//            let mut end = None;
//            for i in 0..8 {
//                if let Some(brn_info) = info[i] {
//                    println!("[PD] \tidx {} {:?}", i, brn_info);
//                    match brn_info { 
//                        // Terminates a basic block
//                        BranchInfo::CallDirect { lr, simm } => {
//                            let base_pc = stage.pc.wrapping_add(i*4);
//                            let tgt = (base_pc as u32).wrapping_add(simm as u32) 
//                                as usize;
//                            println!("{:08x}", tgt);
//                            terminal = CfmEntryKind::ExitStatic {
//                                idx: i, tgt: tgt
//                            };
//                            end = Some(i);
//                            break;
//                        },
//                        _ => unimplemented!("{:?}", brn_info),
//                    }
//
//                }
//            }
//
//            let pdstage = PredecodeStage { 
//                pc: stage.pc,
//                spec: stage.spec,
//                blk: stage.blk,
//                end: end
//            };
//            self.pdstage = Some(pdstage);
//
//        }
//    }
//
//    /// Instruction decode
//    pub fn decode(&mut self) {
//        if let Some(stage) = &self.pdstage { 
//            println!("[DE] pc={:08x} spec={}", stage.pc, stage.spec);
//            let words: [u32; 8] = unsafe { std::mem::transmute(stage.blk) };
//            let mut window = [Instr::Illegal(0xdeadc0de); 8];
//            let end = stage.end.unwrap_or(8-1);
//            for i in 0..=end {
//                window[i] = Rv32::decode(words[i]);
//                println!("[DE] \t{}", window[i]);
//            }
//
//            let dstage = DecodeStage { 
//                pc: stage.pc, 
//                spec: stage.spec,
//                window: window,
//                end: stage.end,
//            };
//            self.dstage = Some(dstage);
//        }
//    }
//
//    pub fn rename(&mut self) { 
//        if let Some(stage) = &self.dstage { 
//        }
//    }


