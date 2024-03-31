use std::fmt::Debug;
use std::cell::*;
use std::rc::*;

use crate::lle::*;
use crate::lle::register::*;

pub struct SyncReadPort<D: Copy + Default> {
    en: Reg<bool>,
    idx: Reg<usize>,
    data: Reg<D>,
}
impl <D: Copy + Default> SyncReadPort<D> {
    pub fn new() -> Self {
        Self { 
            en: Reg::new(false),
            idx: Reg::new(0),
            data: Reg::new(D::default()),
        }
    }
    pub fn drive_en(&mut self, en: bool) { self.en.drive(en); }
    pub fn drive_idx(&mut self, idx: usize) { self.idx.drive(idx); }
    pub fn drive_data(&mut self, data: D) { self.data.drive(data); }
    pub fn sample_en(&self) -> bool { self.en.sample() }
    pub fn sample_idx(&self) -> usize { self.idx.sample() }
    pub fn sample_data(&self) -> D { self.data.sample() }

}
impl <D: Copy + Default> Clocked for SyncReadPort<D> {
    fn update(&mut self) {
        self.en.update();
        self.idx.update();
        self.data.update();
    }
}

pub struct SyncWritePort<D: Copy + Default> {
    en: Reg<bool>,
    idx: Reg<usize>,
    data: Reg<D>,
}
impl <D: Copy + Default> SyncWritePort<D> {
    pub fn new() -> Self {
        Self { 
            en: Reg::new(false),
            idx: Reg::new(0),
            data: Reg::new(D::default()),
        }
    }
}
impl <D: Copy + Default> Clocked for SyncWritePort<D> {
    fn update(&mut self) {
        self.en.update();
        self.idx.update();
        self.data.update();
    }
}



/// Memory element (synchronous read, synchronous write).
/// Dynamically-provisioned read and write ports. 
///
/// FIXME: What to do about bypassing between ports?
pub struct SyncMem<D: Copy + Default, const SZ: usize> {
    data: [ Reg<D>; SZ ],
    rp: Vec<SyncReadPort<D>>,
    wp: Vec<SyncWritePort<D>>,

}
impl <D: Copy + Default, const SZ: usize> SyncMem<D, SZ> {
    pub fn new_init_array(init: &[D; SZ]) -> Self { 
        let data: [Reg<D>; SZ] = init.map(Reg::new);
        Self { 
            data,
            rp: Vec::new(),
            wp: Vec::new(),
        }
    }
    pub fn new_init_val(init: D) -> Self { 
        Self { 
            data: [ Reg::new(init); SZ ],
            rp: Vec::new(),
            wp: Vec::new(),
        }
    }

    pub fn with_read_ports(mut self, num: usize) -> Self { 
        for _ in 0..num { self.rp.push(SyncReadPort::new()); }
        self
    }
    pub fn with_write_ports(mut self, num: usize) -> Self { 
        for _ in 0..num { self.wp.push(SyncWritePort::new()); }
        self
    }
    pub fn new_read_port(&mut self) -> usize {
        let rp_idx = self.rp.len();
        self.rp.push(SyncReadPort::new());
        rp_idx
    }
    pub fn new_write_port(&mut self) -> usize {
        let wp_idx = self.rp.len();
        self.wp.push(SyncWritePort::new());
        wp_idx
    }
    pub fn num_read_ports(&self) -> usize { self.rp.len() }
    pub fn num_write_ports(&self) -> usize { self.wp.len() }

    pub fn drive_rp(&mut self, rp: usize, en: bool, idx: usize) {
        self.rp[rp].drive_en(en);
        self.rp[rp].drive_idx(idx);
    }
    pub fn sample_rp(&self, rp: usize) -> D {
        self.rp[rp].sample_data()
    }


}
impl <D: Copy + Default, const SZ: usize> Clocked for SyncMem<D, SZ> {
    fn update(&mut self) {
        for rp in self.rp.iter_mut() {
            rp.sample_en
        }
    }
}



//// FIXME: Should "sampling" also mean sampling the index?
//pub struct SyncReadPort<I, D: Copy> {
//    idx: Option<I>,
//    data: Option<D>,
//}
//impl <I, D: Copy> SyncReadPort<I, D> {
//    pub fn drive(&mut self, idx: I) { self.idx = Some(idx); }
//    pub fn update(&mut self, data: D) { self.data = Some(data); }
//    pub fn sample(&self) -> Option<D> { self.data }
//    pub fn clear(&mut self) {
//        self.idx = None;
//        self.data = None;
//    }
//}
//
//pub struct SyncWritePort<I, D> {
//    state: Option<(I,D)>,
//}
//impl <I, D> SyncWritePort<I, D> {
//    pub fn clear(&mut self) { self.state = None; }
//    pub fn drive(&mut self, idx: I, data: D) { 
//        self.state = Some((idx, data)); 
//    }
//}
//
//
//
///// Register file with synchronous read, statically-provisioned ports, and
///// no write-to-read forwarding.
//pub struct SyncRegisterFile<D: Copy, 
//    const SZ: usize, const NUM_RP: usize, const NUM_WP: usize> 
//{
//    data: [D; SZ],
//    rp: [SyncReadPort<usize, D>; NUM_RP],
//    wp: [SyncWritePort<usize, D>; NUM_WP],
//}
//impl <D: Copy, const SZ: usize, const NUM_RP: usize, const NUM_WP: usize>
//SyncRegisterFile<D, SZ, NUM_RP, NUM_WP> 
//{
//    pub fn drive_wp(&mut self, port: usize, idx: usize, data: D) {
//        self.wp[port].drive(idx, data);
//    }
//    pub fn drive_rp(&mut self, port: usize, idx: usize) {
//        self.rp[port].drive(idx);
//    }
//    pub fn sample_rp(&self, port: usize) -> Option<D> {
//        self.rp[port].sample()
//    }
//
//    pub fn update(&mut self) {
//        for rp in self.rp.iter_mut() {
//            if let Some(idx) = rp.idx.take() {
//                rp.update(self.data[idx]);
//            }
//        }
//        for wp in &mut self.wp {
//            if let Some((idx, data)) = wp.state.take() {
//                self.data[idx] = data;
//            }
//        }
//    }
//}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn syncmem_init_val() {
        let mem = SyncMem::<u32, 32>::new_init_val(0);
    }

    #[test]
    fn syncmem_init_array() {
        let mem = SyncMem::<u32, 32>::new_init_array(&[0; 32]);
    }

    #[test]
    fn syncmem_create_ports() {
        let mut mem = SyncMem::<u32, 32>::new_init_val(0)
            .with_read_ports(1)
            .with_write_ports(1);
        assert_eq!(mem.num_read_ports(), 1);
        assert_eq!(mem.num_write_ports(), 1);
        mem.new_read_port();
        mem.new_write_port();
        assert_eq!(mem.num_read_ports(), 2);
        assert_eq!(mem.num_write_ports(), 2);
    }

    #[test]
    fn syncmem_readwrite() {
        let mut mem = SyncMem::<u32, 32>::new_init_val(0xdeadbeef)
            .with_read_ports(2)
            .with_write_ports(1);

        assert_eq!(mem.sample_rp(0), 0x00000000);
        mem.drive_rp(0, true, 0);
        assert_eq!(mem.sample_rp(0), 0x00000000);

        mem.update();

        assert_eq!(mem.sample_rp(0), 0xdeadbeef);


    }


}




