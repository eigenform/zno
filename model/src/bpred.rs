
use crate::pipeline::Pipeline;
use crate::rv32i::*;
use std::collections::*;

/// Branch prediction stage. 
///
/// NOTE: The calling convention specifies x1 as the return address, 
/// and x5 as an alternate link register.
///
pub fn bpu_stage(p: &mut Pipeline) {
    if let Some(cfm_addr) = p.nbp.output() {

        println!("[BP] Checking CFM entry {:08x}", cfm_addr);
        let entry = p.cfm.get(cfm_addr).unwrap();

        // Calculate the program counter at the branch instruction
        let brn_pc = cfm_addr.wrapping_add(entry.offset as u32 * 4);

        match entry.instr.branch_info() {
            _ => unimplemented!(),
        }

    } else {
        println!("[BP] No prediction");
    }
}


#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CfmEntry {
    /// The offset index to the branch instruction within the associated
    /// decode window. 
    ///
    /// NOTE: This should probably be an offset to the branch *in bytes*, 
    /// and not an index (this assumes the decode window is always 
    /// an array of u32).
    offset: usize,

    /// Cached branch instruction
    instr: Instr,
}
impl CfmEntry {
    pub fn new(offset: usize, instr: Instr) -> Self { 
        Self { offset, instr }
    }
    pub fn get_instr(&self) -> Instr {
        self.instr
    }
    pub fn get_offset(&self) -> usize {
        self.offset
    }

}


pub struct ControlFlowMap {
    data: BTreeMap<u32, CfmEntry>,
    /// Write port
    input: Option<(u32, CfmEntry)>,
    /// Flush port
    input_flush: Option<u32>,
}
impl ControlFlowMap {
    pub fn new() -> Self {
        Self { 
            data: BTreeMap::new(),
            input: None,
            input_flush: None,
        }
    }
    pub fn get(&self, addr: u32) -> Option<&CfmEntry> {
        self.data.get(&addr)
    }
    pub fn insert(&mut self, addr: u32, e: CfmEntry) {
        self.data.insert(addr, e);
    }
    pub fn submit(&mut self, addr: u32, e: CfmEntry) {
        self.input = Some((addr, e));
    }
    pub fn update(&mut self) {
        if let Some(addr) = &self.input_flush {
            self.data.remove(addr);
            self.input_flush = None;
        }

        if let Some((addr, e)) = &self.input {
            self.data.insert(*addr, *e);
            self.input = None;
        }
    }

    pub fn flush(&mut self, addr: u32) {
        self.input_flush = Some(addr);
    }
}

//#[derive(Clone, Copy, Debug, Default)]
//pub struct Prediction {
//    pub valid: bool,
//    /// Address of the associated CFM entry
//    pub cfm_addr: u32,
//    /// Predicted branch target address
//    pub tgt_addr: u32,
//}



