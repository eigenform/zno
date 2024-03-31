
extern crate goblin;
use goblin::*;

/// Simple random-access memory device. 
pub struct Ram {
    data: Vec<u8>,
    size: usize,
}
impl Ram {
    pub fn new(size: usize) -> Self {
        Self { 
            data: vec![0u8; size],
            size,
        }
    }
    pub fn read_bytes(&self, off: usize, dst: &mut [u8]) {
        assert!(off + dst.len() < self.size);
        dst.copy_from_slice(&self.data[off..(off + dst.len())])
    }
    pub fn write_bytes(&mut self, off: usize, src: &[u8]) {
        assert!(off + src.len() < self.size);
        self.data[off..(off + src.len())].copy_from_slice(src)
    }
}


pub fn read_prog(ram: &mut Ram, filename: &'static str) -> usize { 
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

#[derive(Copy, Clone, Debug)]
pub struct VirtualAddress(usize);
impl VirtualAddress {
    pub fn new(x: usize) -> Self { Self(x) }
}

#[derive(Copy, Clone, Debug)]
pub struct PhysicalAddress(usize);
impl PhysicalAddress {
    pub fn new(x: usize) -> Self { Self(x) }
}

