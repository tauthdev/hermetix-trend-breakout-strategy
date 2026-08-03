package com.tripleauth.trendbreakout

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class TrendBreakoutApplication

fun main(args: Array<String>) {
    runApplication<TrendBreakoutApplication>(*args)
}
