
use std::collections::*;

pub struct AsyncReadCam<K: Ord + Copy, V: Copy> {
    pub wp_pending: Vec<(K, V)>,
    pub data: BTreeMap<K, V>,
}
impl <K: Ord + Copy, V: Copy> AsyncReadCam<K, V> {
    pub fn new() -> Self {
        Self {
            wp_pending: Vec::new(),
            data: BTreeMap::new(),
        }
    }

    // Combinational reads (in hardware this is extraordinarily expensive)
    pub fn sample_rp(&self, key: K) -> Option<&V> {
        self.data.get(&key)
    }

    pub fn drive_wp(&mut self, key: K, value: V) {
        self.wp_pending.push((key, value));
    }

    pub fn update(&mut self) {
        while let Some((k,v)) = self.wp_pending.pop() {
            self.data.insert(k, v);
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


