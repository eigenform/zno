use std::fmt::Debug;
use std::cell::*;
use std::rc::*;

use crate::lle::*;
use crate::lle::register::*;

/// Memory element (asynchronous read, synchronous write)
pub struct Mem<D: Copy + Default, const SZ: usize> {
    data: [ Reg<D>; SZ ],
}
impl <D: Copy + Default, const SZ: usize> Mem<D, SZ> {
    pub fn new_init_val(init: D) -> Self { 
        Self { data: [ Reg::new(init); SZ ] }
    }
    pub fn new_init_array(init: &[D; SZ]) -> Self { 
        let data: [Reg<D>; SZ] = init.map(Reg::new);
        Self { data }
    }

    pub fn drive(&mut self, idx: usize, val: D) { 
        self.data[idx].drive(val);
    }
    pub fn sample(&self, idx: usize) -> D {
        self.data[idx].sample()
    }
    pub fn update(&mut self) {
        for r in self.data.iter_mut() {
            r.update();
        }
    }
}
impl <D: Copy + Default, const SZ: usize> 
std::ops::Index<usize> for Mem<D, SZ> 
{
    type Output = D;
    fn index(&self, idx: usize) -> &Self::Output {
        self.data[idx].sample_ref()
    }
}
impl <D: Copy + Default, const SZ: usize> 
std::ops::IndexMut<usize> for Mem<D, SZ> 
{
    fn index_mut(&mut self, idx: usize) -> &mut Self::Output {
        self.data[idx].next_as_mut()
    }
}



// NOTE: Temporary hack until I deal with [Mem] and [SyncMem]
pub struct RegisterFile<T: Copy + Default + Debug, const SIZE: usize> 
    where T: Copy + Default + Debug
{
    data:  [T; SIZE],
    input: [Option<T>; SIZE],
}
impl <T, const SIZE: usize> RegisterFile<T, SIZE>
    where T: Copy + Default + Debug
{
    pub fn new_init(init: [T; SIZE]) -> Self { 
        Self { 
            data:  init,
            input: [None; SIZE],
        }
    }

    pub fn read(&self, idx: usize) -> T {
        self.data[idx]
    }
    pub fn write(&mut self, idx: usize, val: T) {
        self.input[idx] = Some(val);
    }
}
impl <T, const SIZE: usize> Clocked for RegisterFile<T, SIZE> 
    where T: Copy + Default + Debug
{
    fn update(&mut self) {
        for idx in 0..SIZE { 
            if let Some(val) = self.input[idx].take() {
                self.data[idx] = val;
            }
        }
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn mem_init_val() {
        let mem = Mem::<u32, 32>::new_init_val(0);
    }

    #[test]
    fn mem_init_array() {
        let mem = Mem::<u32, 32>::new_init_array(&[0; 32]);
    }

    #[test]
    fn mem_readwrite() {
        let mut mem = Mem::<u32, 32>::new_init_val(0xdeadbeef);

        assert_eq!(mem.sample(0), 0xdeadbeef);
        assert_eq!(mem.sample(1), 0xdeadbeef);
        mem.drive(1, 0xdeadc0de);
        assert_eq!(mem.sample(0), 0xdeadbeef);
        assert_eq!(mem.sample(1), 0xdeadbeef);

        mem.update();
        assert_eq!(mem.sample(0), 0xdeadbeef);
        assert_eq!(mem.sample(1), 0xdeadc0de);

        mem.update();
        assert_eq!(mem.sample(0), 0xdeadbeef);
        assert_eq!(mem.sample(1), 0xdeadc0de);
    }

}




