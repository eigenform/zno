
pub mod packet;
pub mod queue;
pub mod cam;

pub use crate::sim::state::*;
pub use cam::*;
pub use queue::*;

use std::collections::*;

#[derive(Clone, Copy)]
pub struct Reg<T: Copy + Default> {
    next: Option<T>,
    data: T,
}
impl <T: Copy + Default> Reg<T> {
    pub fn new(init: T) -> Self { 
        Self { next: None, data: init }
    }
    pub fn next_as_mut(&mut self) -> &mut T {
        self.next.get_or_insert(T::default())
    }
    pub fn drive(&mut self, val: T) { self.next = Some(val) }
    pub fn sample(&self) -> T { self.data }
    pub fn sample_ref(&self) -> &T { &self.data }
}
impl <T: Copy + Default> Clocked for Reg<T> {
    fn update(&mut self) {
        if let Some(next) = self.next.take() {
            self.data = next;
        }
    }
}



// FIXME: Should "sampling" also mean sampling the index?
pub struct SyncReadPort<I, D: Copy> {
    idx: Option<I>,
    data: Option<D>,
}
impl <I, D: Copy> SyncReadPort<I, D> {
    pub fn drive(&mut self, idx: I) { self.idx = Some(idx); }
    pub fn update(&mut self, data: D) { self.data = Some(data); }
    pub fn sample(&self) -> Option<D> { self.data }
    pub fn clear(&mut self) {
        self.idx = None;
        self.data = None;
    }
}

pub struct SyncWritePort<I, D> {
    state: Option<(I,D)>,
}
impl <I, D> SyncWritePort<I, D> {
    pub fn clear(&mut self) { self.state = None; }
    pub fn drive(&mut self, idx: I, data: D) { 
        self.state = Some((idx, data)); 
    }
}

pub struct Mem<D: Copy + Default, const SZ: usize> {
    data: [ Reg<D>; SZ ],
}
impl <D: Copy + Default, const SZ: usize> Mem<D, SZ> {
    pub fn new(init: D) -> Self { 
        Self { data: [ Reg::new(init); SZ ] }
    }
    pub fn drive(&mut self, idx: usize, val: D) { 
        self.data[idx].drive(val);
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



/// Register file with synchronous read, statically-provisioned ports, and
/// no write-to-read forwarding.
pub struct SyncRegisterFile<D: Copy, 
    const SZ: usize, const NUM_RP: usize, const NUM_WP: usize> 
{
    data: [D; SZ],
    rp: [SyncReadPort<usize, D>; NUM_RP],
    wp: [SyncWritePort<usize, D>; NUM_WP],
}
impl <D: Copy, const SZ: usize, const NUM_RP: usize, const NUM_WP: usize>
SyncRegisterFile<D, SZ, NUM_RP, NUM_WP> 
{
    pub fn drive_wp(&mut self, port: usize, idx: usize, data: D) {
        self.wp[port].drive(idx, data);
    }
    pub fn drive_rp(&mut self, port: usize, idx: usize) {
        self.rp[port].drive(idx);
    }
    pub fn sample_rp(&self, port: usize) -> Option<D> {
        self.rp[port].sample()
    }

    pub fn update(&mut self) {
        for rp in self.rp.iter_mut() {
            if let Some(idx) = rp.idx.take() {
                rp.update(self.data[idx]);
            }
        }
        for wp in &mut self.wp {
            if let Some((idx, data)) = wp.state.take() {
                self.data[idx] = data;
            }
        }
    }
}






