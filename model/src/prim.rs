

use crate::state::Clocked;

/// Common interface for microarchitectural storage elements which have
/// some kind of underlying notion of "a capacity," and where storage
/// elements can be "occupied" by some data. 
///
/// `CAP` indicates the capacity, and `ISIZE` indicates the maximum number
/// of elements that can be enqueued in a single cycle. 
///
pub trait Storage<const CAP: usize, const ISIZE: usize>: Clocked {

    /// Returns the capacity of this object. 
    fn capacity(&self) -> usize { CAP }

    /// Returns true when the object is full.
    fn is_full(&self) -> bool { self.num_used() == CAP }

    /// Returns true when the object is empty.
    fn is_empty(&self) -> bool { self.num_used() == 0 }

    /// Return the number of free storage elements.
    fn num_free(&self) -> usize { CAP - self.num_used() }

    /// Return the maximum number of entries that can be filled this cycle.
    fn num_in(&self) -> usize {
        if self.num_free() >= ISIZE { ISIZE } else { self.num_free() }
    }

    /// Return the number of occupied storage elements.
    fn num_used(&self) -> usize;

}
