
pub mod packet;
pub mod queue;

use std::collections::*;

/// Content-addressible memory with 1-cycle read/write
pub struct SyncReadCam<K: Ord, V: Copy, const NUM_RP: usize> {
    pub next: Option<(K, V)>,
    pub wp_pending: Vec<(K, V)>,

    pub rp_key: [Option<K>; NUM_RP],
    pub rp_val: [Option<V>; NUM_RP],

    pub data: BTreeMap<K, V>,
}
impl <K: Ord, V: Copy, const NUM_RP: usize> SyncReadCam<K, V, NUM_RP> {
    /// Drive a new element (available on the next cycle).
    pub fn write(&mut self, k: K, v: V) {
        self.wp_pending.push((k, v));
    }

    pub fn drive_rp(&mut self, port: usize, k: K) {
        self.rp_key[port] = Some(k);
    }
    pub fn sample_rp(&self, port: usize) -> Option<V> {
        self.rp_val[port]
    }

    pub fn update(&mut self) {
        for idx in 0..NUM_RP {
            if let Some(key) = self.rp_key[idx].take() {
                if let Some(value) = self.data.get(&key) {
                    self.rp_val[idx] = Some(*value);
                } else {
                    self.rp_val[idx] = None;
                }
            } else {
                self.rp_val[idx] = None;
            }
        }
    }
}

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

    /// Get the oldest entry in the queue (if it exists).
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


