
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
