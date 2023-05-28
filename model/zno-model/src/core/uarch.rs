
use crate::riscv::rv32i::*;

/// Immediate storage strategy. 
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ImmStorage { 
    /// Indicates the lack of an immediate value (nothing to store).
    None, 
    /// Indicates that an immediate value is zero (requires no storage).
    Zero, 
    /// Indicates that an immediate value is small enough to alias with 
    /// a physical register name (physical design optimization).
    //Inline, 
    /// Indicates that storage for an immediate value must be allocated.
    Alloc,
}
impl ImmStorage {
    pub fn from_imm19(imm19: u32) -> Self {
        if imm19 == 0 {
            Self::Zero
        } else {
            Self::Alloc
        }
    }
}

/// Immediate control bits.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ImmCtl {
    /// Indicates how immediate data is stored in the pipeline.
    pub storage: ImmStorage,
    /// Indicates how immediate data should be expanded into a 32-bit value.
    pub fmt: ImmFormat,
}

pub struct FetchBlock {
    pub addr: usize,
    pub data: [u8; 0x20],
}
impl FetchBlock {
    pub fn as_words(&self) -> &[u32; 8] {
        unsafe { std::mem::transmute(&self.data) }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PredecodeInfo {
    pub illegal: bool,
    pub imm_ctl: ImmCtl,
    pub imm_data: ImmData,
    pub brn_kind: Option<BranchKind>,
}
impl PredecodeInfo {
    pub fn is_branch(&self) -> bool {
        !self.illegal && self.brn_kind.is_some()
    }
}
impl Default for PredecodeInfo {
    fn default() -> Self { 
        Self {
            illegal: true,
            imm_ctl: ImmCtl { storage: ImmStorage::None, fmt: ImmFormat::None },
            imm_data: ImmData { sign: false, imm19: 0, },
            brn_kind: None,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct PredecodeBlock {
    pub addr: usize,
    pub data: [u8; 0x20],
    pub info: [PredecodeInfo; 8],
}
impl PredecodeBlock {
    pub fn as_words(&self) -> &[u32; 8] {
        unsafe { std::mem::transmute(&self.data) }
    }
    pub fn to_cfm_entry(&self) -> CfmEntry {
        //for (idx, info) in self.info.iter().enumerate() {
        //    let pc = self.addr + (idx*4);
        //    println!("{:08x}: {:x?}", pc, info);
        //}

        // This predecode block contains no branches
        if !self.info.iter().any(|info| info.is_branch()) {
            return CfmEntry {
            };
        }

        // The predecode block contains an illegal instruction.
        let ill_idx = self.info.iter().enumerate()
            .find(|(idx, info)| info.illegal)
            .map(|(idx, info)| idx);
        if let Some(idx) = ill_idx {
            if idx == 0 {
                unimplemented!("The first instruction in this block is illegal");
            }
            unimplemented!("This block has an illegal instruction");
        }

        let branches: Vec<(usize, &PredecodeInfo)> = self.info.iter()
            .enumerate().filter(|(idx, info)| info.is_branch())
            .map(|(idx, info)| (idx, info))
            .collect();
        for (idx, info) in branches {
            println!("  {} {:?}", idx, info);
        }

        CfmEntry {
        }
    }
}

pub struct DecodeBlock {
    pub addr: usize,
    pub data: [Instr; 8],
}

#[derive(Clone, Copy, Debug)]
pub struct CfmEntry {
}



