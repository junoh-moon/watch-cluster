package com.watchcluster

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WatchClusterApplication

fun main(args: Array<String>) {
    runApplication<WatchClusterApplication>(*args)
}
