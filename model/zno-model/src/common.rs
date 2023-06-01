
pub mod packet;
pub mod queue;

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
    pub fn update(&mut self) {
        if let Some(next) = self.next.take() {
            self.data = next;
        }
    }
}

pub struct ValidReg<T> {
    data: Option<T>,
}
impl <T> ValidReg<T> {
    pub fn new_valid(init: T) -> Self { 
        Self { data: Some(init) }
    }
    pub fn new_invalid() -> Self { 
        Self { data: None }
    }
    pub fn sample(&self) -> &Option<T> { &self.data }
    pub fn drive_valid(&mut self, val: T) { self.data = Some(val) }
    pub fn invalidate(&mut self) { self.data = None }
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


#[derive(Clone, Copy)]
pub struct SyncReadCamOutput<K: Copy, V: Copy> {
    pub index: K,
    pub data: Option<V>,
}

/// Content-addressible memory with synchronous read/write behavior and 
/// statically-provisioned read/write ports. 
pub struct SyncReadCam<K: Ord + Copy, V: Copy, 
    const NUM_RP: usize, const NUM_WP: usize> 
{
    pub wp_pending: [Option<(K, V)>; NUM_WP],

    pub rp_key: [Option<K>; NUM_RP],
    pub rp_val: [Option<SyncReadCamOutput<K, V>>; NUM_RP],

    pub data: BTreeMap<K, V>,

    pub update_fn: Option<fn(&mut Self)>,
}
impl <K: Ord + Copy, V: Copy, const NUM_RP: usize, const NUM_WP: usize> 
SyncReadCam<K, V, NUM_RP, NUM_WP> 
{
    pub fn new() -> Self {
        Self {
            wp_pending: [None; NUM_WP],
            rp_key: [None; NUM_RP],
            rp_val: [None; NUM_RP],
            data: BTreeMap::new(),
            update_fn: None,
        }
    }
    pub fn set_update_fn(&mut self, update_fn: fn(&mut Self)) {
        self.update_fn = Some(update_fn);
    }

    /// Drive a new element (available on the next cycle).
    pub fn drive_wp(&mut self, port: usize, k: K, v: V) {
        self.wp_pending[port] = Some((k, v));
    }

    /// Drive a read port
    pub fn drive_rp(&mut self, port: usize, k: K) {
        self.rp_key[port] = Some(k);
    }

    /// Sample result from a read port
    pub fn sample_rp(&self, port: usize) -> Option<SyncReadCamOutput<K, V>> {
        self.rp_val[port]
    }

    pub fn update(&mut self) {
        if let Some(update_fn) = self.update_fn {
            (update_fn)(self);
        } else {
            self.default_update();
        }
    }

    // Default update strategy.
    fn default_update(&mut self) {
        for idx in 0..NUM_RP {
            if let Some(key) = self.rp_key[idx].take() {
                // Output is valid and we return the matching data
                if let Some(value) = self.data.get(&key) {
                    self.rp_val[idx] = Some(
                        SyncReadCamOutput { index: key, data: Some(*value) }
                    );
                } 
                // Output is valid, but there's no match
                else {
                    self.rp_val[idx] = Some(
                        SyncReadCamOutput { index: key, data: None }
                    );
                }
            } 
            // Output is invalid
            else {
                self.rp_val[idx] = None;
            }
        }
    }
}


/// Simple queue implementation. 
pub struct Queue<T> {
    pub next: Option<T>,
    pub deq_ok: bool,
    pub data: VecDeque<T>,
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

    /// Get a reference to the oldest entry in the queue (if it exists).
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


