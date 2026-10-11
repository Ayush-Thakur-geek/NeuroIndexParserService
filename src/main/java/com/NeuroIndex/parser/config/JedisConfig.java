package com.NeuroIndex.parser.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.UnifiedJedis;

@Configuration
public class JedisConfig {
    @Bean
    public UnifiedJedis jedis() {
        return new UnifiedJedis("http://localhost:6380");
    }
}
