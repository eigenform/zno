
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
#[derive(Clone, Copy, Debug)]
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

#[derive(Clone, Copy)]
pub struct PredecodeInfo {
    pub illegal: bool,
    pub imm_ctl: ImmCtl,
    pub imm_data: ImmData,
}
impl Default for PredecodeInfo {
    fn default() -> Self { 
        Self {
            illegal: true,
            imm_ctl: ImmCtl { storage: ImmStorage::None, fmt: ImmFormat::None },
            imm_data: ImmData { sign: false, imm19: 0, },
        }
    }
}

pub struct PredecodeBlock {
    pub addr: usize,
    pub data: [u8; 0x20],
    pub info: [PredecodeInfo; 8],
}
impl PredecodeBlock {
    pub fn as_words(&self) -> &[u32; 8] {
        unsafe { std::mem::transmute(&self.data) }
    }
}

pub struct DecodeBlock {
    pub addr: usize,
    pub data: [Instr; 8],
}



