package com.watchcluster.util

fun <T> Sequence<T>.zipLongest(other: Sequence<T>, defaultValue: T): Sequence<Pair<T, T>> =
    (this + generateSequence { defaultValue }).zip(other + generateSequence { defaultValue })
        .take(maxOf(this.count(), other.count()))

fun <T> List<T>.zipLongest(other: List<T>, defaultValue: T): Sequence<Pair<T, T>> =
    this.asSequence().zipLongest(other.asSequence(), defaultValue)
        .take(maxOf(this.count(), other.count()))
