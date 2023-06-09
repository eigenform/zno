
use std::collections::*;

/// Simple queue implementation. 
///
/// FIXME: This is a high-level version (and there's no bound on the size!).
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

pub struct CircularQueue<T: Copy, const SZ: usize> {
    pub next: Option<T>,
    pub enq_ptr: usize,
    pub deq_ptr: usize,
    pub deq_ok: bool,
    pub data: [Option<T>; SZ],

    // NOTE: This is a [Vec] because we're dynamically allocating write ports
    // based on the usage of [update_idx]. 
    pub wp_pending: Vec<(usize, T)>,
}
impl <T: Copy, const SZ: usize> CircularQueue<T, SZ> {
    pub fn new() -> Self {
        Self {
            next: None,
            enq_ptr: 0,
            deq_ptr: 0,
            deq_ok: false,
            data: [None; SZ],

            wp_pending: Vec::new(),
        }
    }

    // Random access asynchronous read into the queue with a pointer 'idx'. 
    // Uses of this function represents an asynchronous read-port. 
    pub fn sample_idx(&self, idx: usize) -> Option<T> {
        assert!(idx < SZ);
        self.data[idx]
    }

    // Random access synchronous write into the queue with a pointer 'idx'. 
    // Uses of this function represents a synchronous write-port. 
    pub fn update_idx(&mut self, idx: usize, value: T) {
        assert!(self.data[idx].is_some());
        self.wp_pending.push((idx, value));
    }

    // Random access read to determine if pointer 'idx' is valid.
    pub fn is_valid(&self, idx: usize) -> bool {
        assert!(idx < SZ);
        self.data[idx].is_some()
    }

    pub fn is_full(&self) -> bool {
        self.enq_ptr == self.deq_ptr && self.data[self.enq_ptr].is_some()
    }
    pub fn is_empty(&self) -> bool {
        self.enq_ptr == self.deq_ptr && self.data[self.deq_ptr].is_none()
    }

    // Drive an entry to be enqueued on the following cycle. 
    //
    // Returns the current 'enq_ptr' when allocation is allowed.
    // Otherwise, this function returns [None] when the queue is full.
    pub fn enq(&mut self, value: T) -> Option<usize> {
        if self.is_full() {
            None
        } else {
            self.next = Some(value);
            Some(self.enq_ptr)
        }
    }

    pub fn set_deq(&mut self) {
        self.deq_ok = true;
    }

    pub fn front(&self) -> Option<T> {
        self.data[self.deq_ptr]
    }

    fn inc_deq_ptr(&mut self) {
        if self.deq_ptr == SZ - 1 {
            self.deq_ptr = 0;
        } else {
            self.deq_ptr += 1;
        }
    }
    fn inc_enq_ptr(&mut self) {
        if self.deq_ptr == SZ - 1 {
            self.deq_ptr = 0;
        } else {
            self.deq_ptr += 1;
        }
    }


    pub fn update(&mut self) {
        // Each entry in 'wp_pending' corresponds to a write port being
        // driven on the current cycle. 
        while let Some((idx, value)) = self.wp_pending.pop() {
            assert!(self.data[idx].is_some());
            self.data[idx] = Some(value);
        }

        // FIXME: actually handle overflow conditions

        if self.deq_ok {
            self.data[self.deq_ptr] = None;
            self.deq_ok = false;
            self.inc_deq_ptr();
        }

        if let Some(next) = self.next.take() {
            self.data[self.enq_ptr] = Some(next);
            self.inc_enq_ptr();
        }


    }
}


